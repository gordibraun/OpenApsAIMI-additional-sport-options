package app.aaps.wear.combo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The watch end of adb-through-the-phone (see AdbTunnelServer in the phone's combowatch driver):
 * a Wear channel opened by the phone at /combowatch/adb/<port> is joined to 127.0.0.1:<port> on
 * this watch, where adbd listens after `adb tcpip <port>` was run once over USB (it forgets on a
 * reboot). Bytes pass through untouched, so the computer's adb key must still be one this watch
 * trusts. Only a connected phone can open a channel to this app, and only debuggable builds join it.
 */
internal object AdbTunnel {

    const val PATH_PREFIX = "/combowatch/adb/"
    const val DEFAULT_PORT = 5555
    const val EXTRA_CHANNEL = "channel"
    const val TAG = "AdbTunnel"
}

class AdbTunnelListenerService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(AdbTunnel.PATH_PREFIX)) return
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.w(AdbTunnel.TAG, "release build: channel ${channel.path} refused")
            Wearable.getChannelClient(this).close(channel)
            return
        }
        ContextCompat.startForegroundService(
            this, Intent(this, AdbTunnelService::class.java).putExtra(AdbTunnel.EXTRA_CHANNEL, channel)
        )
    }
}

/** Keeps the relays alive as a foreground service; stops itself when the last one ends. */
class AdbTunnelService : Service() {

    private val active = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground(active.get())
        val channel = intent?.let { IntentCompat.getParcelableExtra(it, AdbTunnel.EXTRA_CHANNEL, ChannelClient.Channel::class.java) }
        if (channel == null) {
            if (active.get() == 0) stopSelf()
            return START_NOT_STICKY
        }
        showForeground(active.incrementAndGet())
        thread(name = "adb-tunnel", isDaemon = true) { serve(channel) }
        return START_NOT_STICKY
    }

    private fun serve(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        var socket: Socket? = null
        val streams = mutableListOf<java.io.Closeable>()
        try {
            val port = channel.path.removePrefix(AdbTunnel.PATH_PREFIX).toIntOrNull()?.takeIf { it in 1..65535 } ?: AdbTunnel.DEFAULT_PORT
            val fromPhone = Tasks.await(client.getInputStream(channel), 20, TimeUnit.SECONDS).also { streams += it }
            val toPhone = Tasks.await(client.getOutputStream(channel), 20, TimeUnit.SECONDS).also { streams += it }
            val s = Socket().apply { tcpNoDelay = true }
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
            socket = s
            streams += s.getInputStream()
            streams += s.getOutputStream()
            Log.i(AdbTunnel.TAG, "channel joined to 127.0.0.1:$port")
            val closed = AtomicBoolean(false)
            val lock = Object()
            var running = 2
            val finish = {
                if (closed.compareAndSet(false, true)) streams.forEach { runCatching { it.close() } }
                synchronized(lock) { running--; lock.notifyAll() }
            }
            pump("adb-tunnel phone>adbd", fromPhone, s.getOutputStream(), finish)
            pump("adb-tunnel adbd>phone", s.getInputStream(), toPhone, finish)
            synchronized(lock) { while (running > 0) lock.wait() }
        } catch (t: Throwable) {
            Log.w(AdbTunnel.TAG, "relay failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            streams.forEach { runCatching { it.close() } }
            runCatching { socket?.close() }
            runCatching { Tasks.await(client.close(channel), 5, TimeUnit.SECONDS) }
            val left = active.decrementAndGet()
            Log.i(AdbTunnel.TAG, "relay ended, $left left")
            if (left <= 0) stopSelf() else showForeground(left)
        }
    }

    private fun pump(label: String, from: InputStream, to: OutputStream, onDone: () -> Unit) = thread(name = label, isDaemon = true) {
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // the other side closed
        } finally {
            onDone()
        }
    }

    private fun showForeground(count: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Канал отладки", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("adb через телефон")
            .setContentText(if (count > 0) "Соединений: $count" else "Ожидание")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            startForeground(NOTIFICATION_ID, notification)
    }

    private companion object {

        const val CHANNEL_ID = "adb_tunnel"
        const val NOTIFICATION_ID = 7441
    }
}
