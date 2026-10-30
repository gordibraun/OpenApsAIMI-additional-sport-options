package app.aaps.pump.combowatch

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * adb to the watch through the phone, so the watch need never come off the wrist for an install,
 * a log or a journal:
 *
 *   Mac adb  --USB-->  phone adbd  --adb forward-->  127.0.0.1:4444 here  --Wear channel (Bluetooth)-->
 *   AdbTunnelService on the watch  -->  127.0.0.1:5555, the watch's own adbd (after `adb tcpip 5555` once per watch boot)
 *
 * Each TCP client accepted here gets a channel of its own to the watch; bytes pass through both ways
 * untouched, so adb's handshake and key authentication work as over a cable: only a computer whose key
 * the watch already trusts gets in, and an unknown one makes the watch ask. Listens on the loopback
 * only, is started and stopped from the adb shell ([AdbTunnelReceiver]), and stops itself after a quiet
 * spell. Debuggable builds only.
 */
@Singleton
class AdbTunnelServer @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private var server: ServerSocket? = null
    private val relays = CopyOnWriteArrayList<AdbTunnelRelay>()
    @Volatile private var lastActivityMs = 0L
    private var listenPort = DEFAULT_LISTEN_PORT
    private var watchPort = DEFAULT_WATCH_PORT
    private var idleMs = 0L
    private var connectionsTotal = 0
    @Volatile private var nodeName: String? = null
    @Volatile private var lastError: String? = null
    private val statusFile: File get() = File(context.filesDir, STATUS_FILE)

    /** Listen on 127.0.0.1:[listenPort]; every client is tunnelled to 127.0.0.1:[watchPort] on the watch. */
    @Synchronized
    fun start(listenPort: Int, watchPort: Int, idleMs: Long): JSONObject {
        if (server != null) stop()
        this.listenPort = listenPort
        this.watchPort = watchPort
        this.idleMs = idleMs
        lastError = null
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), listenPort))
        server = socket
        lastActivityMs = System.currentTimeMillis()
        thread(name = "adb-tunnel-accept", isDaemon = true) { acceptLoop(socket) }
        thread(name = "adb-tunnel-idle", isDaemon = true) { idleLoop(socket) }
        aapsLogger.info(LTag.PUMP, "combowatch adb tunnel: listening on 127.0.0.1:$listenPort -> watch 127.0.0.1:$watchPort, stops after ${idleMs / 60_000} quiet min")
        return writeStatus()
    }

    @Synchronized
    fun stop(): JSONObject {
        val socket = server
        server = null
        runCatching { socket?.close() }
        relays.toList().forEach { it.close() }
        if (socket != null) aapsLogger.info(LTag.PUMP, "combowatch adb tunnel: stopped")
        return writeStatus()
    }

    fun status(): JSONObject = writeStatus()

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            lastActivityMs = System.currentTimeMillis()
            synchronized(this) { connectionsTotal++ }
            thread(name = "adb-tunnel-conn", isDaemon = true) { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        val channelClient = Wearable.getChannelClient(context)
        var channel: ChannelClient.Channel? = null
        try {
            client.tcpNoDelay = true
            val node = pickNode() ?: error("no watch connected to the phone")
            nodeName = node.displayName
            val opened = Tasks.await(channelClient.openChannel(node.id, "$PATH_PREFIX$watchPort"), 20, TimeUnit.SECONDS)
            channel = opened
            val toWatch = Tasks.await(channelClient.getOutputStream(opened), 20, TimeUnit.SECONDS)
            val fromWatch = Tasks.await(channelClient.getInputStream(opened), 20, TimeUnit.SECONDS)
            val relay = AdbTunnelRelay(
                "adb-tunnel", client.getInputStream(), client.getOutputStream(), fromWatch, toWatch,
                onActivity = { lastActivityMs = System.currentTimeMillis() },
                onClosed = { ended ->
                    runCatching { client.close() }
                    runCatching { Tasks.await(channelClient.close(opened), 5, TimeUnit.SECONDS) }
                    relays.remove(ended)
                    aapsLogger.debug(LTag.PUMP, "combowatch adb tunnel: connection closed, ${ended.bytesAtoB} B to the watch, ${ended.bytesBtoA} B back")
                    writeStatus()
                }
            )
            relays += relay
            relay.start()
            aapsLogger.debug(LTag.PUMP, "combowatch adb tunnel: connection open via ${node.displayName}")
            writeStatus()
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            aapsLogger.warn(LTag.PUMP, "combowatch adb tunnel: connection failed: $lastError")
            runCatching { client.close() }
            channel?.let { runCatching { Tasks.await(channelClient.close(it), 5, TimeUnit.SECONDS) } }
            writeStatus()
        }
    }

    private fun pickNode(): Node? {
        val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 10, TimeUnit.SECONDS)
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    private fun idleLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                Thread.sleep(15_000)
            } catch (_: InterruptedException) {
                return
            }
            if (idleMs > 0 && relays.isEmpty() && System.currentTimeMillis() - lastActivityMs > idleMs && !socket.isClosed) {
                aapsLogger.info(LTag.PUMP, "combowatch adb tunnel: quiet for ${idleMs / 60_000} min, closing")
                stop()
                return
            }
        }
    }

    @Synchronized
    private fun writeStatus(): JSONObject {
        val json = JSONObject()
            .put("running", server?.isClosed == false)
            .put("listenPort", listenPort)
            .put("watchPort", watchPort)
            .put("connections", relays.size)
            .put("connectionsTotal", connectionsTotal)
            .put("bytesToWatch", relays.sumOf { it.bytesAtoB })
            .put("bytesFromWatch", relays.sumOf { it.bytesBtoA })
            .put("node", nodeName ?: JSONObject.NULL)
            .put("lastError", lastError ?: JSONObject.NULL)
            .put("at", System.currentTimeMillis())
        runCatching { statusFile.writeText(json.toString()) }
        return json
    }

    companion object {

        /** The channel path; the watch reads the port to connect to off its end. */
        const val PATH_PREFIX = "/combowatch/adb/"
        const val DEFAULT_LISTEN_PORT = 4444
        const val DEFAULT_WATCH_PORT = 5555
        const val STATUS_FILE = "combowatch-adb-tunnel.json"
    }
}
