package app.aaps.rfcommpeer;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A transport-only negative control. No pump library, commands, keys, or network API. */
@SuppressLint({"MissingPermission", "UnspecifiedRegisterReceiverFlag"})
public final class PeerService extends Service {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile BluetoothSocket socket;
    private volatile BluetoothServerSocket server;
    private volatile String trial = "observer";
    private BluetoothAdapter adapter;
    private String peer;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !peer.equalsIgnoreCase(device.getAddress())) return;
            record(trial, intent.getAction(), "bond", device.getBondState(),
                "transport", intent.getIntExtra("android.bluetooth.device.extra.TRANSPORT", -1),
                "previousBond", intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1));
            if (busy.get() && (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction()) ||
                (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()) &&
                    device.getBondState() != BluetoothDevice.BOND_BONDED))) {
                record(trial, "UNEXPECTED_PAIRING");
                closeSockets();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("peer", "Bluetooth peer test", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "peer").setContentTitle("Bluetooth peer test")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build());
        peer = PeerPolicy.peer(Build.MODEL);
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
        registered = true;
        record("observer", "SERVICE_STARTED", "peer", peer, "sdk", Build.VERSION.SDK_INT);
        // A lost host connection cannot leave diagnostics running indefinitely.
        timer.schedule(() -> { stopSelf(); }, 45, TimeUnit.MINUTES);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getStringExtra("mode");
        String id = intent.getStringExtra("id");
        int delay = intent.getIntExtra("delayMs", 0);
        try {
            PeerPolicy.validate(Build.MODEL, intent.getStringExtra("peer"), id, delay);
            if ("stop".equals(action)) { stopSelf(); return START_NOT_STICKY; }
            if ("status".equals(action)) {
                record(id, "STATUS", "bond", adapter.getRemoteDevice(peer).getBondState(), "enabled", adapter.isEnabled(), "busy", busy.get());
            } else if ("pair".equals(action)) {
                if (busy.get()) throw new IllegalStateException("Trial active");
                record(id, "PAIR_REQUEST", "accepted", adapter.getRemoteDevice(peer).createBond());
            } else if ("server".equals(action) || "client".equals(action)) {
                if (!busy.compareAndSet(false, true)) throw new IllegalStateException("Trial active");
                trial = id;
                boolean secure = intent.getBooleanExtra("secure", false);
                boolean spp = intent.getBooleanExtra("spp", false);
                record(id, "SCHEDULED", "mode", action, "delayMs", delay, "secure", secure, "spp", spp);
                worker.schedule(() -> runTrial(id, action, secure, spp), delay, TimeUnit.MILLISECONDS);
            } else throw new IllegalArgumentException("Unknown mode");
        } catch (Exception error) { record("rejected", "REJECTED", "error", error.toString()); }
        return START_NOT_STICKY;
    }

    private void runTrial(String id, String mode, boolean secure, boolean spp) {
        AtomicBoolean expired = new AtomicBoolean();
        PowerManager.WakeLock lock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rfcommpeer:attempt");
        ScheduledFuture<?> deadline = null;
        long begin = SystemClock.elapsedRealtime();
        String outcome = "FAILED";
        try {
            lock.acquire(28000);
            BluetoothDevice remote = adapter.getRemoteDevice(peer);
            if (!adapter.isEnabled() || remote.getBondState() != BluetoothDevice.BOND_BONDED) throw new IllegalStateException("Existing bond required");
            record(id, "START", "mode", mode, "secure", secure, "spp", spp,
                "discoveryActive", adapter.isDiscovering(), "wakeLockHeld", lock.isHeld());
            deadline = timer.schedule(() -> { expired.set(true); closeSockets(); }, 25, TimeUnit.SECONDS);
            UUID uuid = UUID.fromString(spp ? PeerPolicy.SERIAL_UUID : PeerPolicy.UUID);
            if ("server".equals(mode)) {
                server = secure ? adapter.listenUsingRfcommWithServiceRecord("BT Peer Probe", uuid)
                    : adapter.listenUsingInsecureRfcommWithServiceRecord("BT Peer Probe", uuid);
                if (expired.get()) throw new IllegalStateException("Deadline");
                record(id, "LISTENING");
                socket = server.accept(22000);
                if (!peer.equalsIgnoreCase(socket.getRemoteDevice().getAddress())) throw new IllegalStateException("Unexpected peer");
            } else {
                if (adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                    long waitEnd = SystemClock.elapsedRealtime() + 2000;
                    while (adapter.isDiscovering() && SystemClock.elapsedRealtime() < waitEnd) Thread.sleep(50);
                    if (adapter.isDiscovering()) throw new IllegalStateException("Discovery not stopped");
                }
                socket = secure ? remote.createRfcommSocketToServiceRecord(uuid) : remote.createInsecureRfcommSocketToServiceRecord(uuid);
                if (expired.get()) throw new IllegalStateException("Deadline");
                record(id, "CONNECTING");
                socket.connect();
            }
            if (expired.get() || remote.getBondState() != BluetoothDevice.BOND_BONDED) throw new IllegalStateException("Deadline or lost bond");
            record(id, "SOCKET_OPEN", "openDurationMs", SystemClock.elapsedRealtime() - begin);
            Thread.sleep("server".equals(mode) ? 3000 : 2000);
            if (expired.get()) throw new IllegalStateException("Deadline");
            outcome = "OK_ZERO_BYTES";
        } catch (Exception error) {
            record(id, "ERROR", "type", error.getClass().getSimpleName(), "message", error.getMessage());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (deadline != null) deadline.cancel(false);
            closeSockets();
            if (lock.isHeld()) lock.release();
            record(id, "FINISHED", "outcome", expired.get() ? "TIMEOUT" : outcome,
                "durationMs", SystemClock.elapsedRealtime() - begin, "applicationBytesSent", 0);
            busy.set(false);
        }
    }

    private synchronized void closeSockets() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        socket = null;
        server = null;
    }

    private synchronized void record(String id, String event, Object... values) {
        try {
            PowerManager power = getSystemService(PowerManager.class);
            JSONObject row = new JSONObject().put("id", id).put("event", event)
                .put("wallMs", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime())
                .put("interactive", power.isInteractive()).put("deviceIdle", power.isDeviceIdleMode())
                .put("powerSave", power.isPowerSaveMode());
            for (int i = 0; i < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "events.jsonl"), true)) {
                out.write((row + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Log.i("RfcommPeer", row.toString());
        } catch (Exception error) { Log.e("RfcommPeer", "Cannot record event", error); }
    }

    @Override public void onDestroy() {
        closeSockets();
        worker.shutdownNow();
        timer.shutdownNow();
        if (registered) unregisterReceiver(receiver);
        record("observer", "SERVICE_STOPPED");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
