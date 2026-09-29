package app.aaps.combobench

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class BenchRuntime private constructor(private val context: Context) {
    private val files = BenchFiles(context)
    val manualPairing = ManualComboPairing(context)
    private val worker = Executors.newSingleThreadExecutor()
    private var ownership: DiagnosticOwnership? = null
    private var identity: BenchIdentity? = null
    private var pumpAddress: String? = null
    private var localNode = ""
    private var peers = JSONArray()
    private var error = ""
    private var peerReport: JSONObject? = null
    private var probeReport: JSONObject? = null
    private var pairingReport: JSONObject? = null
    private var pairingSession: BluetoothPairingSession? = null
    private val events = ArrayDeque<String>()
    @Volatile private var report = JSONObject().put("status", "Ожидание связи").toString()

    init {
        if (files.exists("status.json")) {
            runCatching {
                val saved = files.read("status.json")
                probeReport = saved.optJSONObject("probe")
                pairingReport = saved.optJSONObject("pairing")
                if (pairingReport?.optBoolean("active") == true) {
                    pairingReport?.put("active", false)?.put("stage", "INTERRUPTED")
                        ?.put("detail", "Стенд перезапущен во время сопряжения; результат неизвестен, повторные действия заблокированы")
                }
                saved.optJSONArray("events")?.let { array ->
                    for (i in maxOf(0, array.length() - 40) until array.length()) events.addLast(array.getString(i))
                }
            }.onFailure { error = "Diagnostic history could not be read" }
        }
    }

    fun snapshot() = JSONObject(report)

    fun startManualPairing(offBodyConfirmed: Boolean, phoneDisabledConfirmed: Boolean) {
        worker.execute {
            var operation: String? = null
            try {
                check(offBodyConfirmed && phoneDisabledConfirmed) { "Нужно подтвердить условия стенда" }
                discover()
                configure()
                check(BuildConfig.FLAVOR == "watch") { "Этот этап разрешён только на часах" }
                val controller = ownership!!
                check(controller.state().owner == identity!!.local) {
                    "Право проверки находится на телефоне. В стенде телефона нажмите «Передать проверку»"
                }
                check(controller.state().operation == null) {
                    "Предыдущая проверка не завершена. Повторное сопряжение пока заблокировано"
                }
                val id = UUID.randomUUID().toString()
                controller.beginOperation(id)
                operation = id
                manualPairing.start(pumpAddress!!, identity!!.pump) { settled -> worker.execute {
                    if (settled) runCatching { controller.completeOperation(id) }
                        .onFailure { error = "Не удалось сохранить завершение; повторные действия заблокированы" }
                    else error = "Результат сопряжения неизвестен; повторные действия заблокированы"
                    publish()
                } }
                error = ""
            } catch (e: Exception) {
                operation?.let { ownership?.completeOperation(it) }
                manualPairing.recordStartFailure(e.javaClass.simpleName)
                error = e.message ?: "Не удалось начать сопряжение"
            } finally { publish() }
        }
    }

    fun run(command: String, finished: () -> Unit = {}) {
        worker.execute {
            try {
                if (command == "manual-pairing-stop") {
                    manualPairing.cancel(ManualComboPairing.CancelOrigin.HOST)
                    return@execute
                }
                discover()
                if (command != "discover" || files.exists("config.json")) configure()
                when (command) {
                    "discover" -> Unit
                    "refresh" -> {
                        send("hello")
                        ownership!!.state().outbox?.let { send("grant", it.toJson()) }
                    }
                    "transfer" -> {
                        val grant = ownership!!.transfer(UUID.randomUUID().toString())
                        event("Право проверки передано; ожидается подтверждение")
                        publish()
                        send("grant", grant.toJson())
                    }
                    "probe" -> probe()
                    "control-session" -> {
                        check(pairingSession == null && !manualPairing.snapshot().optBoolean("active"))
                        val id = identity!!
                        val operation = files.consumeControlSessionArm(id.session, id.pump)
                        val controller = ownership!!
                        controller.beginOperation(operation)
                        if (ControlSessionDiagnostic(context).run(operation, pumpAddress!!, id.pump)) {
                            controller.completeOperation(operation)
                        }
                        event("Диагностический контрольный сеанс завершён; результат в control-session.json")
                    }
                    "peer-server", "peer-client" -> {
                        check(pairingSession == null && !manualPairing.snapshot().optBoolean("active"))
                        check(ownership!!.state().operation == null)
                        PeerSocketControl(context).run(command.removePrefix("peer-"), identity!!.session)
                    }
                    "prepare-repairing" -> {
                        check(BuildConfig.FLAVOR == "watch" && pairingSession == null)
                        val controller = ownership!!
                        val operation = UUID.randomUUID().toString()
                        files.consumePairingArm(identity!!.session, identity!!.pump)
                        controller.beginOperation(operation)
                        // On an unexpected storage failure the operation stays locked for inspection.
                        manualPairing.prepareRePairing(pumpAddress!!, identity!!.pump)
                        controller.completeOperation(operation)
                        event("Прежняя привязка стенда архивирована; подготовлено ручное повторное сопряжение")
                    }
                    "pairing-start" -> startPairing()
                    "pairing-stop" -> pairingSession?.stop()
                    "manual-pairing-stop" -> manualPairing.cancel(ManualComboPairing.CancelOrigin.HOST)
                    else -> error("Unknown bench command")
                }
                error = ""
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
                event("Ошибка: $error")
            } finally {
                publish()
                finished()
            }
        }
    }

    fun pairingVisibilityResult(id: String, resultCode: Int?) {
        worker.execute {
            if (pairingReport?.optString("id") == id) pairingSession?.visibilityResult(resultCode)
        }
    }

    private fun startPairing() {
        check(pairingSession == null) { "Pairing is already active" }
        val controller = ownership!!
        val operation = UUID.randomUUID().toString()
        controller.beginOperation(operation)
        try {
            files.consumePairingArm(identity!!.session, identity!!.pump)
            pairingReport = JSONObject().put("at", System.currentTimeMillis()).put("id", operation)
                .put("stage", "STARTING").put("active", true).put("applicationBytesSent", 0)
            val session = BluetoothPairingSession(context, pumpAddress!!,
                diagnostic = { detail -> worker.execute { event(detail); publish() } },
                update = { stage, detail -> worker.execute {
                    pairingReport?.put("stage", stage)?.put("detail", detail)
                    event(detail)
                    publish()
                } },
                finished = { stage, detail, settled -> worker.execute {
                    pairingReport?.put("stage", stage)?.put("detail", detail)?.put("active", false)
                    pairingSession = null
                    if (settled) runCatching { controller.completeOperation(operation) }
                        .onFailure { error = "Pairing state is locked: ${it.message}" }
                    else error = "Pairing outcome is unresolved; diagnostic ownership remains locked"
                    event(detail)
                    publish()
                } })
            pairingSession = session
            session.start()
        } catch (e: Exception) {
            if (pairingSession == null) controller.completeOperation(operation)
            else pairingSession?.stop("FAILED", "Не удалось начать Bluetooth-сопряжение: ${e.javaClass.simpleName}")
            throw e
        }
    }

    fun receive(source: String, payload: ByteArray) {
        worker.execute {
            try {
                require(payload.size <= 16_384)
                discover()
                configure()
                val id = identity!!
                val message = JSONObject(payload.toString(Charsets.UTF_8))
                require(source == id.peer && message.getString("session") == id.session)
                require(message.getInt("protocol") == 1 && message.getString("pump") == id.pump)
                when (message.getString("kind")) {
                    "hello" -> send("status", ownership!!.state().toJson())
                    "status" -> peerReport = message.getJSONObject("body").put("receivedAt", System.currentTimeMillis())
                    "grant" -> {
                        val grant = message.getJSONObject("body").toGrant()
                        ownership!!.accept(source, grant)
                        event("Получено право проверки, поколение ${grant.generation}")
                        publish()
                        send("ack", grant.toJson())
                    }
                    "ack" -> {
                        ownership!!.acknowledge(source, message.getJSONObject("body").toGrant())
                        event("Передача подтверждена вторым устройством")
                    }
                    else -> error("Unknown bench message")
                }
                error = ""
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
                event("Сообщение отклонено: $error")
            } finally { publish() }
        }
    }

    private fun discover() {
        val client = Wearable.getNodeClient(context)
        localNode = Tasks.await(client.localNode, 5, TimeUnit.SECONDS).id
        peers = JSONArray()
        Tasks.await(client.connectedNodes, 5, TimeUnit.SECONDS).forEach { node ->
            peers.put(JSONObject().put("id", node.id).put("name", node.displayName).put("nearby", node.isNearby))
        }
    }

    private fun configure() {
        if (ownership != null) {
            check(identity!!.local == localNode) { "Local Wear node changed" }
            return
        }
        val config = files.read("config.json")
        require(config.getInt("protocol") == 1 && config.getBoolean("diagnosticOnly"))
        val id = BenchIdentity(config.getString("session"), config.getString("local"), config.getString("peer"), config.getString("pump"))
        require(id.local == localNode) { "Configuration belongs to another Wear node" }
        val address = config.getString("address")
        require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address))
        val store = object : OwnershipStore {
            override fun load(): OwnershipState {
                val data = files.read("ownership.json")
                require(data.getString("session") == id.session && data.getString("pump") == id.pump)
                require(data.getString("local") == id.local && data.getString("peer") == id.peer)
                return data.getJSONObject("state").toOwnershipState()
            }
            override fun save(state: OwnershipState) {
                files.write("ownership.json", JSONObject().put("session", id.session).put("pump", id.pump)
                    .put("local", id.local).put("peer", id.peer).put("state", state.toJson()))
            }
        }
        val controller = DiagnosticOwnership(id, store)
        controller.state()
        identity = id
        pumpAddress = address
        ownership = controller
    }

    private fun send(kind: String, body: JSONObject = JSONObject()) {
        val id = identity!!
        val payload = JSONObject().put("protocol", 1).put("session", id.session).put("pump", id.pump)
            .put("kind", kind).put("body", body).toString().toByteArray(Charsets.UTF_8)
        Tasks.await(Wearable.getMessageClient(context).sendMessage(id.peer, PATH, payload), 5, TimeUnit.SECONDS)
    }

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi", "UnspecifiedRegisterReceiverFlag")
    private fun probe() {
        val controller = ownership!!
        val id = identity!!
        val operation = UUID.randomUUID().toString()
        check(pairingSession == null && !manualPairing.snapshot().optBoolean("active")) { "Pairing must be inactive" }
        controller.beginOperation(operation)
        var settled = true
        try {
            val options = files.consumeArm(id.session, id.pump)
            val transport = options.transport
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            check(adapter != null && adapter.isEnabled) { "Bluetooth is disabled" }
            val device = adapter.getRemoteDevice(pumpAddress!!)
            val initialBondState = device.bondState
            val bondGuard = ProbeBondGuard(initialBondState == BluetoothDevice.BOND_BONDED)
            if (transport.authenticated) bondGuard.requireExistingBond()
            val socket = when (transport) {
                ProbeTransport.SDP -> device.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                ProbeTransport.SDP_AUTHENTICATED -> device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                // Bench-only compatibility experiment. No hidden-API policy changes or secure fallback.
                ProbeTransport.CHANNEL_ONE -> BluetoothDevice::class.java
                    .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
                ProbeTransport.CHANNEL_ONE_AUTHENTICATED -> BluetoothDevice::class.java
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            }
            val timeout = Executors.newSingleThreadScheduledExecutor()
            val deadline = ProbeDeadline()
            val aclDisconnected = AtomicBoolean(false)
            val start = SystemClock.elapsedRealtime()
            var openedAtElapsed: Long? = null
            var holdTask: java.util.concurrent.ScheduledFuture<*>? = null
            val result = JSONObject().put("at", System.currentTimeMillis()).put("bondState", initialBondState)
                .put("applicationBytesSent", 0).put("address", pumpAddress).put("transport", transport.wireName)
                .put("apkVersionCode", BuildConfig.VERSION_CODE).put("apkVersionName", BuildConfig.VERSION_NAME)
                .put("timeoutSeconds", options.timeoutSeconds).put("holdSeconds", options.holdSeconds)
                .put("holdCompleted", false)
            val power = context.getSystemService(PowerManager::class.java)
            val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComboBench:socket-probe")
            result.put("context", JSONObject().put("adapterState", adapter.state).put("scanMode", adapter.scanMode)
                .put("discovering", adapter.isDiscovering).put("interactive", power.isInteractive)
                .put("deviceIdle", power.isDeviceIdleMode).put("powerSave", power.isPowerSaveMode))
            result.put("phase", "preparing")
            val linkEvents = Collections.synchronizedList(mutableListOf<JSONObject>())
            val receiver = object : BroadcastReceiver() {
                @Suppress("DEPRECATION")
                override fun onReceive(context: Context, intent: Intent) {
                    val remote = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (remote.address != pumpAddress) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, remote.bondState)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, state)
                    bondGuard.observe(state == BluetoothDevice.BOND_BONDED,
                        pairingRequested = intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST,
                        bondChanged = intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED && state != previous)
                    linkEvents.add(JSONObject().put("action", intent.action)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - start)
                        .put("bondState", state))
                    if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) aclDisconnected.set(true)
                    // Never answer a PIN request or confirm pairing in a socket diagnostic.
                    if (bondGuard.stopReason != null) runCatching { socket.close() }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            }
            var receiverRegistered = false
            probeReport = result.put("stage", "RFCOMM_CONNECTING")
            event("Подключение RFCOMM; отправка команд отсутствует")
            publish()
            val closeTask = timeout.schedule({
                if (deadline.expireConnection()) runCatching { socket.close() }
            }, options.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            try {
                // Keep the bounded diagnostic timers running without lighting the screen.
                wakeLock.acquire(options.permissionBudgetMs + 3000L)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receiver, filter)
                receiverRegistered = true
                val preparation = ProbePreparation.stopDiscovery(
                    { adapter.isDiscovering }, { adapter.cancelDiscovery() }, SystemClock::elapsedRealtime, Thread::sleep)
                result.put("preparation", JSONObject().put("discoveryWasActive", preparation.wasActive)
                    .put("cancelDiscoveryAccepted", preparation.cancellationAccepted).put("discoveryAfter", adapter.isDiscovering)
                    .put("elapsedMs", preparation.elapsedMs))
                result.put("phase", "socket_connect").put("connectStartedAfterMs", SystemClock.elapsedRealtime() - start)
                settled = false
                bondGuard.observe(device.bondState == BluetoothDevice.BOND_BONDED)
                check(bondGuard.stopReason == null) { "Bond changed before connect" }
                socket.connect()
                if (SystemClock.elapsedRealtime() - start >= options.timeoutSeconds * 1000L) deadline.expireConnection()
                check(deadline.opened()) { "RFCOMM connection timeout" }
                closeTask.cancel(false)
                openedAtElapsed = SystemClock.elapsedRealtime()
                result.put("openedAt", System.currentTimeMillis()).put("connectDurationMs", openedAtElapsed - start)
                bondGuard.observe(device.bondState == BluetoothDevice.BOND_BONDED)
                check(bondGuard.stopReason == null) { "Pairing state changed during connect" }
                result.put("phase", "socket_open")
                event("RFCOMM открыт. Байтов команд отправлено: 0")
                if (options.holdSeconds > 0) {
                    result.put("phase", "socket_hold")
                    // The grace period bounds a stuck hold; it does not extend the connection deadline.
                    holdTask = timeout.schedule({
                        if (deadline.expireHold()) runCatching { socket.close() }
                    }, (options.holdSeconds + 2).toLong(), TimeUnit.SECONDS)
                    val holdUntil = openedAtElapsed + options.holdSeconds * 1000L
                    do {
                        check(!deadline.expired) { "Socket hold watchdog expired" }
                        bondGuard.observe(device.bondState == BluetoothDevice.BOND_BONDED)
                        check(bondGuard.stopReason == null) { "Pairing state changed during hold" }
                        check(!aclDisconnected.get() && socket.isConnected) { "Link closed during hold" }
                        val remaining = holdUntil - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        Thread.sleep(minOf(remaining, 50L))
                    } while (true)
                    result.put("holdElapsedMs", SystemClock.elapsedRealtime() - openedAtElapsed)
                }
                check(deadline.finish()) { "Diagnostic deadline expired" }
                result.put("holdCompleted", true).put("stage", "RFCOMM_OK_ZERO_BYTES")
                result.put("phase", "socket_open")
            } catch (e: Exception) {
                result.put("stage", if (deadline.expired) "RFCOMM_TIMEOUT" else "RFCOMM_FAILED")
                    .put("error", "${e.javaClass.simpleName}: ${e.message}")
                event("RFCOMM: ${result.getString("stage")}")
            } finally {
                closeTask.cancel(false)
                holdTask?.cancel(false)
                openedAtElapsed?.let { result.put("closeRequestedAfterOpenMs", SystemClock.elapsedRealtime() - it) }
                result.put("closeRequestedAt", System.currentTimeMillis())
                runCatching { socket.close() }
                timeout.shutdownNow()
                if (wakeLock.isHeld) wakeLock.release()
                if (receiverRegistered) context.unregisterReceiver(receiver)
                val finalBondState = device.bondState
                bondGuard.observe(finalBondState == BluetoothDevice.BOND_BONDED)
                settled = bondGuard.stopReason == null && finalBondState != BluetoothDevice.BOND_BONDING
                if (!settled) {
                    result.put("stage", "RFCOMM_PAIRING_BLOCKED").put("stopReason", bondGuard.stopReason ?: "BOND_UNRESOLVED")
                    event("Сопряжение изменилось или запрошено заново; повторные проверки заблокированы")
                }
                result.put("finalBondState", finalBondState).put("ownershipLocked", !settled)
                result.put("linkEvents", synchronized(linkEvents) { JSONArray(linkEvents.toList()) })
                result.put("durationMs", SystemClock.elapsedRealtime() - start)
                result.put("watchdogExpired", deadline.expired).put("deadlinePhase", deadline.phase.name)
                result.put("aclDisconnectedDuringProbe", aclDisconnected.get())
                probeReport = result
            }
        } finally {
            // An interrupted probe or observed re-pairing requires investigation, not an automatic retry.
            if (settled) controller.completeOperation(operation)
        }
    }

    private fun event(message: String) {
        events.addLast("${System.currentTimeMillis()} $message")
        while (events.size > 40) events.removeFirst()
    }

    private fun publish() {
        val data = JSONObject().put("at", System.currentTimeMillis()).put("model", Build.MODEL)
            .put("localNode", localNode).put("peers", peers).put("diagnosticOnly", true)
            .put("error", error).put("probe", probeReport).put("peerReport", peerReport)
            .put("pairing", pairingReport)
            .put("manualPairing", manualPairing.snapshot())
            .put("events", JSONArray(events.toList()))
        identity?.let { data.put("session", it.session).put("pump", it.pump).put("peer", it.peer) }
        try { ownership?.state()?.let { data.put("ownership", it.toJson()) } }
        catch (e: Exception) { data.put("error", "Controller locked: ${e.message}") }
        report = data.toString()
        try { files.write("status.json", data) } catch (e: Exception) {
            error = "Status persistence failed: ${e.message}"
            report = data.put("error", error).toString()
        }
    }

    companion object {
        const val PATH = "/combo-bench/v1"
        // Only applicationContext is retained, never an Activity or Service.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: BenchRuntime? = null
        fun get(context: Context): BenchRuntime = instance ?: synchronized(this) {
            instance ?: BenchRuntime(context.applicationContext).also { instance = it }
        }
    }
}
