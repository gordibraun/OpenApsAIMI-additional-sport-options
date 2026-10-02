package app.aaps.combobench

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice as SystemDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import info.nightscout.comboctl.android.AndroidBluetoothDevice
import info.nightscout.comboctl.base.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A manually confirmed pairing session. No Pump, PumpManager or dosing API is exposed. */
@SuppressLint("MissingPermission")
internal class ManualComboPairing(private val context: Context) {
    enum class CancelOrigin { USER, HOST, TIMEOUT }
    private val files = BenchFiles(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var bluetoothSession: BluetoothPairingSession? = null
    @Volatile private var device: AndroidBluetoothDevice? = null
    private var pendingPin: CompletableDeferred<PairingPIN>? = null
    private var pinRequestId = ""
    private val deadlineHandler = Handler(Looper.getMainLooper())
    @Volatile private var timedOut = false
    private val deadline = Runnable { timedOut = true; cancel(CancelOrigin.TIMEOUT) }
    private var state = if (files.exists("manual-pairing.json")) files.read("manual-pairing.json") else JSONObject()

    init {
        if (state.optBoolean("active")) {
            state.put("active", false).put("stage", "INTERRUPTED")
                .put("detail", "Приложение перезапущено. Результат неизвестен; повторное сопряжение заблокировано")
            files.write("manual-pairing.json", state)
        }
    }

    @Synchronized fun snapshot() = JSONObject(state.toString())
    @Synchronized fun prepareRePairing(addressText: String, pump: String) {
        check(job?.isActive != true && !state.optBoolean("active")) { "Pairing is active" }
        check(PairingTarget.matches("00:0E:2F:25:24:BC", addressText) && pump == "PUMP_41056642")
        val archive = BenchPairingStore(context, addressText.toBluetoothAddress(), pump).archiveForRePairing()
        state.put("completed", false).put("archivedPairing", archive)
        record("REPAIR_PREPARED", "Старая привязка стенда сохранена в зашифрованном архиве на часах. Можно начать новое сопряжение")
    }
    fun recordStartFailure(errorType: String) = record("START_REJECTED", "Запуск отклонён: $errorType", true)

    /**
     * Close the record of the last pairing after its pump was unpaired, so that the next pairing
     * starts from nothing. The record moves to the history, like every earlier session.
     */
    @Synchronized fun forget() {
        check(job?.isActive != true && !state.optBoolean("active")) { "Pairing is active" }
        // The history is a convenience; failing to write it must not leave the pump half unpaired.
        runCatching { archiveState() }
        state = JSONObject()
        android.util.AtomicFile(java.io.File(context.filesDir, "manual-pairing.json")).delete()
    }

    private fun archiveState() {
        if (!state.has("id")) return
        val history = if (files.exists("manual-pairing-history.json"))
            files.read("manual-pairing-history.json").getJSONArray("sessions") else JSONArray()
        val retained = JSONArray()
        for (i in maxOf(0, history.length() - 7) until history.length()) retained.put(history.getJSONObject(i))
        retained.put(state)
        files.write("manual-pairing-history.json", JSONObject().put("sessions", retained))
    }
    @Synchronized fun visibilityResult(id: String, resultCode: Int?) {
        if (state.optString("id") == id && state.optBoolean("active")) bluetoothSession?.visibilityResult(resultCode)
    }

    @Synchronized fun submitPin(id: String, request: String, value: String): Boolean {
        if (!PairingPinInput.valid(value) || state.optString("id") != id || request != pinRequestId ||
            state.optString("stage") != "PIN_REQUIRED") return false
        val accepted = pendingPin?.complete(PairingPIN(value.map { it - '0' }.toIntArray())) == true
        if (accepted) record("VERIFYING_PIN", "Проверяем код; сам код в журнал не записывается")
        return accepted
    }

    fun cancel(origin: CancelOrigin = CancelOrigin.USER) {
        if (snapshot().optBoolean("active"))
            record("CANCEL_REQUEST", "Источник остановки: ${origin.name}", true)
        job?.cancel(CancellationException("Pairing cancelled: ${origin.name}"))
        bluetoothSession?.stop("CANCELLED", "Сопряжение остановлено: ${origin.name}")
        device?.disconnect()
    }

    @Synchronized private fun record(stage: String, detail: String, eventOnly: Boolean = false) {
        val now = System.currentTimeMillis()
        val events = state.optJSONArray("events") ?: JSONArray()
        val retained = JSONArray()
        for (i in maxOf(0, events.length() - 249) until events.length()) retained.put(events.getJSONObject(i))
        retained.put(JSONObject().put("at", now).put("stage", stage).put("detail", detail))
        state.put("events", retained).put("updatedAt", now)
        if (!eventOnly) state.put("stage", stage).put("detail", detail)
        files.write("manual-pairing.json", state)
    }

    @Synchronized private fun protocolProgress(stage: String, detail: String) {
        // StateFlow may deliver an earlier progress update after the PIN callback.
        if (state.optString("stage") !in setOf("PIN_REQUIRED", "VERIFYING_PIN") || stage == "FINISHING")
            record(stage, detail)
    }

    @Synchronized fun start(addressText: String?, pump: String, finished: (Boolean) -> Unit) {
        check(job?.isActive != true && !state.optBoolean("active")) { "Pairing is already active" }
        val manualTarget = if (BuildConfig.MANUAL_TARGET)
            ManualPumpTarget(pump.removePrefix("PUMP_"), addressText.orEmpty()) else null
        if (manualTarget == null) check(addressText != null && PairingTarget.matches("00:0E:2F:25:24:BC", addressText) && pump == "PUMP_41056642") {
            "Разрешена только тестовая Combo 41056642"
        }
        check(!java.io.File(context.noBackupFilesDir, "combo-pairing.enc").exists() &&
            !java.io.File(context.noBackupFilesDir, "combo-pairing.enc.bak").exists()) {
            "Сопряжение стенда уже сохранено; оно не будет перезаписано"
        }
        archiveState()
        state = JSONObject().put("id", UUID.randomUUID().toString()).put("startedAt", System.currentTimeMillis())
            .put("active", true).put("pump", pump).put("address", addressText)
            .put("confirmation", when {
                !BuildConfig.MANUAL_TARGET        -> "user-confirmed-off-body-and-AAPS-disabled"
                manualTarget?.isTestPump == false -> "user-confirmed-no-other-controller"
                else                              -> "user-confirmed-off-body-and-no-other-controller"
            }).put("dosingAvailable", false)
        record("STARTING", "Подготовка сопряжения Combo ${pump.removePrefix("PUMP_")}")
        try { context.startForegroundService(Intent(context, PairingForegroundService::class.java)) }
        catch (e: Exception) {
            state.put("active", false)
            record("FAILED", "Android не разрешил запустить службу сопряжения")
            throw e
        }
        timedOut = false
        deadlineHandler.postDelayed(deadline, 8 * 60_000L)
        job = scope.launch {
            var completed = false
            var cleanupKnown = true
            var selectedAddress = addressText
            var store: BenchPairingStore? = null
            try {
                withTimeout(8 * 60_000L) {
                    val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java).adapter)
                    check(adapter.isEnabled) { "Bluetooth disabled" }
                    if (manualTarget != null) {
                        // The watch holds no Combo keys at this point (checked before starting),
                        // so a Bluetooth bond with any Combo is a leftover of an earlier pairing.
                        // Android turns away a pairing request from a device it believes it is
                        // bonded with, and the pump only accepts a fresh pairing - so with such
                        // a bond in place the pairing would wait until it times out. The pump is
                        // not known by address yet, hence every Combo bond is cleared.
                        val leftovers = adapter.bondedDevices.orEmpty().filter { manualTarget.isCombo(it.address) }
                        for (leftover in leftovers) {
                            record("RESETTING_BOND", "Удаляем прежнюю Bluetooth-привязку Combo на часах")
                            check(leftover.javaClass.getMethod("removeBond").invoke(leftover) == true) { "Прежняя Bluetooth-привязка не снята" }
                            withTimeout(10_000) { while (leftover.bondState != SystemDevice.BOND_NONE) delay(100) }
                        }
                    }
                    val bonded = CompletableDeferred<Unit>()
                    bluetoothSession = BluetoothPairingSession(context, addressText,
                        update = { stage, detail -> record(stage, detail) },
                        finished = { stage, detail, settled ->
                            cleanupKnown = settled
                            record(stage, detail)
                            if (stage == "BONDED") bonded.complete(Unit)
                            else bonded.completeExceptionally(BluetoothPairingStopped(stage, detail))
                        },
                        diagnostic = { record("BLUETOOTH_EVENT", it, eventOnly = true) },
                        manualTarget = manualTarget,
                        targetSelected = {
                            selectedAddress = it
                            synchronized(this@ManualComboPairing) {
                                state.put("address", it)
                                record("TARGET_FOUND", "Bluetooth-адрес помпы: $it; серийный номер проверим по протоколу", eventOnly = true)
                            }
                        })
                    bluetoothSession!!.start()
                    bonded.await()
                    bluetoothSession = null
                    val address = checkNotNull(selectedAddress).toBluetoothAddress()
                    val sessionStore = BenchPairingStore(context, address, pump)
                    store = sessionStore
                    record("CONNECTING", "Bluetooth сопряжён. Подключаем протокол Combo")
                    // Waiting for a manually entered PIN is allowed to exceed the driver's normal 20s idle watchdog.
                    val transport = AndroidBluetoothDevice(context, adapter, address, watchdogTimeoutMs = 8 * 60_000L)
                    device = transport
                    val incoming = ComboFrameParser()
                    val pairingOnlyTransport = object : BluetoothDevice(Dispatchers.IO) {
                        override val address = transport.address
                        override fun connect() = transport.connect()
                        override fun disconnect() = transport.disconnect()
                        override fun unpair() = transport.unpair()
                        override fun blockingReceive(): List<Byte> {
                            val data = transport.blockingReceive()
                            incoming.pushData(data)
                            while (true) {
                                val frame = incoming.parseFrame() ?: break
                                val packet = frame.toTransportLayerPacket()
                                val name = if (packet.command == TransportLayer.Command.DATA)
                                    ApplicationLayer.Packet(packet).command.name else packet.command.name
                                record("RX", "Получен служебный пакет $name", eventOnly = true)
                            }
                            return data
                        }
                        override fun blockingSend(dataToSend: List<Byte>) {
                            val command = PairingPackets.inspect(dataToSend)
                            record("TX_ATTEMPT", "Отправляем служебный пакет $command", eventOnly = true)
                            transport.blockingSend(dataToSend)
                        }
                    }
                    val progress = ProgressReporter<Unit>(listOf(BasicProgressStage.PerformingConnectionHandshake::class,
                        BasicProgressStage.ComboPairingKeyAndPinRequested::class, BasicProgressStage.ComboPairingFinishing::class), Unit)
                    val collector = launch {
                        progress.progressFlow.collect {
                            when (it.stage) {
                                BasicProgressStage.PerformingConnectionHandshake -> protocolProgress("HANDSHAKE", "Помпа подключена. Начинаем авторизацию Combo")
                                BasicProgressStage.ComboPairingKeyAndPinRequested -> protocolProgress("REQUESTING_PIN", "Запрошен десятизначный код на экране помпы")
                                BasicProgressStage.ComboPairingFinishing -> protocolProgress("FINISHING", "Код принят. Завершаем привязку и сохраняем её на часах")
                            }
                        }
                    }
                    try {
                        PumpIO(sessionStore, pairingOnlyTransport, {}, { error ->
                            record("RX_ERROR", "Ошибка приёма: ${error.javaClass.simpleName}; ${error.cause?.javaClass?.simpleName.orEmpty()}", true)
                        }).performPairing(adapter.name ?: "Combo bench", progress) { newAddress, incorrect ->
                            check(newAddress == address)
                            val pin = CompletableDeferred<PairingPIN>()
                            synchronized(this@ManualComboPairing) {
                                pendingPin = pin
                                pinRequestId = UUID.randomUUID().toString()
                                state.put("pinRequestId", pinRequestId)
                                record("PIN_REQUIRED", if (incorrect) "Код не подошёл. Проверьте десять цифр на помпе и введите ещё раз"
                                    else "Введите десять цифр с экрана помпы")
                            }
                            try { withTimeout(180_000) { pin.await() } }
                            finally { synchronized(this@ManualComboPairing) { pendingPin = null } }
                        }
                        check(sessionStore.getInvariantPumpData(address).pumpID == pump)
                        completed = true
                    } finally { collector.cancelAndJoin() }
                }
                record("PAIRED", if (manualTarget?.isTestPump == false) "Сопряжение Combo завершено. Помпой управляет телефон через AAPS"
                    else "Сопряжение Combo завершено. Команды подачи в стенде недоступны")
            } catch (_: TimeoutCancellationException) {
                record("TIMEOUT", "Время ожидания истекло. Отмените сопряжение на помпе; подробности этапов сохранены в журнале")
            } catch (_: CancellationException) {
                record(if (timedOut) "TIMEOUT" else "CANCELLED", if (timedOut) "Время ожидания истекло; соединение закрыто"
                    else "Сопряжение отменено. На помпе также выйдите из режима сопряжения")
            } catch (e: BluetoothPairingStopped) {
                record(e.stage, e.detail)
            } catch (e: Exception) {
                val cause = e.cause?.javaClass?.simpleName?.let { "; причина: $it" }.orEmpty()
                record("FAILED", "Не удалось завершить сопряжение: ${e.javaClass.simpleName}$cause")
                record("ERROR_LOCATION", e.stackTrace.take(5).joinToString("; ") { "${it.className}.${it.methodName}:${it.lineNumber}" }, true)
            } finally {
                val outcome = snapshot()
                deadlineHandler.removeCallbacks(deadline)
                device?.disconnect()
                device = null
                bluetoothSession?.stop()
                bluetoothSession = null
                synchronized(this@ManualComboPairing) {
                    pendingPin = null
                    state.put("stage", outcome.optString("stage")).put("detail", outcome.optString("detail"))
                    state.put("active", false).put("finishedAt", System.currentTimeMillis()).put("completed", completed)
                    // A stored but unconfirmed state must not silently become a fresh attempt.
                    cleanupKnown = cleanupKnown && (completed || store?.let {
                        !it.hasPumpState(checkNotNull(selectedAddress).toBluetoothAddress())
                    } != false)
                    state.put("cleanupKnown", cleanupKnown)
                    files.write("manual-pairing.json", state)
                }
                context.stopService(Intent(context, PairingForegroundService::class.java))
                finished(cleanupKnown)
            }
        }
    }

    private class BluetoothPairingStopped(val stage: String, val detail: String) : Exception()
}
