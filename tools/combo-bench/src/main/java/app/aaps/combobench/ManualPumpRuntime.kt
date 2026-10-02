package app.aaps.combobench

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * The pump this watch is paired with: pairing it, unpairing it, and the bench's own diagnostics.
 * Nothing here delivers anything; commands from the phone are the controller's business.
 */
internal class ManualPumpRuntime private constructor(context: Context) {
    private val context = context.applicationContext as android.app.Application
    private val files = BenchFiles(context)
    val pairing = ManualComboPairing(context)
    val reconnect = ManualReconnectProbe(context)
    val control = ManualControlSession(context)
    val therapy = ManualTherapySession(context)
    val background = ManualBackgroundProbe(context)
    @Volatile var error = ""
        private set

    /** True while any Bluetooth work of the bench is running or unresolved; therapy sessions never overlap it. */
    fun otherWorkActive(): Boolean = pairing.snapshot().optBoolean("active") || reconnect.isActive() ||
        control.isBlocked() || background.isActive()

    /**
     * True while something of the bench has, or is about to open, a Bluetooth link to the pump.
     * Unlike [otherWorkActive] an experiment that merely ended unclear does not count: that is a
     * matter between the bench and its test pump, and must not keep the controller from the pump
     * its owner paired.
     */
    fun usingBluetoothNow(): Boolean = pairing.snapshot().optBoolean("active") || reconnect.isActive() ||
        control.isActive() || therapy.isActive() || background.isActive()

    fun target(): ManualPumpTarget? = if (files.exists("manual-target.json")) {
        val saved = files.read("manual-target.json")
        ManualPumpTarget(saved.getString("serial"), saved.optString("address"))
    } else null

    /**
     * An experiment of the bench that ended unclear holds further work on the test pump back.
     * It says nothing about any other pump, which only a running session keeps waiting.
     */
    private fun controlInTheWay(target: ManualPumpTarget?): Boolean =
        if (target?.isTestPump == false) control.isActive() else control.isBlocked()

    fun configure(serial: String, address: String) {
        check(BuildConfig.MANUAL_TARGET)
        val target = ManualPumpTarget(serial, address)
        check(!background.isActive()) { "Отложенная проверка уже запланирована" }
        check(!controlInTheWay(target)) { "Служебный сеанс не завершён" }
        check(!therapy.isActive()) { "Сеанс драйвера ещё выполняется" }
        check(!pairing.snapshot().optBoolean("active")) { "Сопряжение уже идёт" }
        check(!reconnect.isActive()) { "Проверка соединения уже идёт" }
        val previous = target()
        check(!hasPairing() || (previous?.serial == target.serial && previous.address == target.address)) {
            "Сохранённая привязка не будет перезаписана"
        }
        files.write("manual-target.json", JSONObject().put("serial", target.serial).put("address", target.address ?: ""))
        error = ""
    }

    fun start(offBody: Boolean, noOtherController: Boolean) {
        try {
            // "Off body" is a condition of the bench's test pump. A pump in use only has to be
            // free of any other controller, because the Combo keeps a single remote.
            val wanted = target()
            check(BuildConfig.MANUAL_TARGET && noOtherController && (offBody || wanted?.isTestPump == false)) {
                "Подтвердите условия привязки"
            }
            check(!background.isActive()) { "Отложенная проверка уже запланирована" }
            check(!controlInTheWay(wanted)) { "Служебный сеанс не завершён" }
            check(!therapy.isActive()) { "Сеанс драйвера ещё выполняется" }
            check(!reconnect.isActive()) { "Проверка соединения уже идёт" }
            check(pairing.snapshot().optString("stage") != "INTERRUPTED" || canRestartPreparation()) {
                "Прошлый сеанс прерван; сначала нужно проверить его результат"
            }
            check(pairing.snapshot().optBoolean("cleanupKnown", true)) { "Результат прошлого сеанса неизвестен; повтор заблокирован" }
            val target = checkNotNull(wanted) { "Сначала укажите помпу" }
            check(!hasPairing()) { "Привязка уже сохранена. Новое сопряжение не требуется" }
            error = ""
            pairing.start(target.address, target.pump) { settled ->
                if (!settled) error = "Результат неизвестен; повторное сопряжение заблокировано"
                // Tells the phone at once which pump this watch holds now.
                runCatching { app.aaps.combobench.controller.ControllerHost.get(context).sendHeartbeat() }
            }
        } catch (e: Exception) {
            error = e.message ?: "Не удалось начать сопряжение"
        }
    }

    fun testReconnect(secure: Boolean = false, direct: Boolean = false, hold: Boolean = false) {
        try {
            error = ""
            check(!background.isActive())
            check(!control.isBlocked()) { "Служебный сеанс не завершён" }
            check(!therapy.isActive()) { "Сеанс драйвера ещё выполняется" }
            reconnect.start(target()?.pump, pairing.snapshot(), secure, direct, hold)
        } catch (e: Exception) { error = e.message ?: "Проверка не запущена" }
    }

    fun checkDisconnect() {
        try {
            error = ""
            check(!background.isActive())
            check(!control.isBlocked()) { "Служебный сеанс не завершён" }
            check(!therapy.isActive()) { "Сеанс драйвера ещё выполняется" }
            check(!pairing.snapshot().optBoolean("active"))
            reconnect.checkDisconnect()
        } catch (e: Exception) { error = e.message ?: "Проверка разрыва не запущена" }
    }

    fun testControlSession(secure: Boolean, direct: Boolean, useAapsTransport: Boolean = false) {
        try {
            error = ""
            check(!background.isActive())
            check(!reconnect.isActive())
            check(!therapy.isActive()) { "Сеанс драйвера ещё выполняется" }
            control.start(target()?.pump, pairing.snapshot(), reconnect.snapshot(), secure, direct, useAapsTransport)
        } catch (_: Exception) { error = "Служебный сеанс не запущен; проверьте журнал и завершение предыдущей проверки" }
    }

    fun scheduleBackgroundProbe(mode: String = ManualBackgroundProbe.MODE_HANDSHAKE) {
        try { error = ""; background.arm(this, mode) }
        catch (_: Exception) { error = "Отложенная проверка не запущена; проверьте журнал" }
    }

    /** Full AAPS driver session on the test pump: status read, delivery stop (0 % TBR) or return to 100 %. */
    fun runTherapy(kind: TherapySessionPolicy.Kind, durationMinutes: Int = 0) {
        try {
            error = ""
            val request = TherapySessionPolicy.request(kind, durationMinutes)
            therapy.start(request, target()?.pump, pairing.snapshot(), reconnect.snapshot(), otherWorkActive())
        } catch (e: Exception) { error = e.message ?: "Сеанс драйвера не запущен; проверьте журнал" }
    }

    fun canRestartPreparation(): Boolean {
        val state = pairing.snapshot()
        val events = state.optJSONArray("events") ?: return false
        return ManualPairingRecovery.canRetry(state.optString("stage"), state.has("address"), hasPairing(),
            (0 until events.length()).map { events.getJSONObject(it).optString("stage") })
    }

    private fun hasPairing() = File(context.noBackupFilesDir, "combo-pairing.enc").exists() ||
        File(context.noBackupFilesDir, "combo-pairing.enc.bak").exists()

    /** The pump this watch is fully paired with, and the Bluetooth address it was found at. */
    class PairedPump(val pump: String, val serial: String, val address: String, val isTestPump: Boolean)

    /**
     * Null unless a pairing ran to its end with the pump that was asked for and its keys are
     * stored. The controller acts on nothing else, so a pairing that was interrupted, or whose
     * record does not match the chosen pump, counts as no pump at all.
     */
    fun pairedPump(): PairedPump? {
        val target = runCatching { target() }.getOrNull() ?: return null
        val state = pairing.snapshot()
        if (!hasPairing() || state.optString("stage") != "PAIRED" || state.optBoolean("active") || !state.optBoolean("completed")) return null
        if (state.optString("pump") != target.pump) return null
        // The address the pump was actually found at during pairing; the entered one is optional.
        val address = state.optString("address").ifEmpty { target.address.orEmpty() }
        if (!PairingTarget.matches(address, address)) return null
        return PairedPump(target.pump, target.serial, address.uppercase(), target.isTestPump)
    }

    /** True when a pairing's keys are stored, whether or not that pairing ran to its end. */
    fun hasStoredKeys(): Boolean = hasPairing()

    /** True when there is anything [unpair] would remove: a chosen pump, stored keys or a pairing record. */
    fun hasSomethingToUnpair(): Boolean =
        runCatching { target() }.getOrNull() != null || hasPairing() || pairing.snapshot().has("id")

    /** What the owner should read before confirming [unpair]; empty when there is nothing to warn about. */
    fun unpairWarnings(): List<String> =
        runCatching { app.aaps.combobench.controller.ControllerHost.get(context).unpairWarnings(storedPumpIsTestPump()) }.getOrDefault(emptyList())

    /** Whether what is stored here - a chosen pump, or failing that a pairing record - is the bench's test pump. */
    private fun storedPumpIsTestPump(): Boolean =
        runCatching { target() }.getOrNull()?.isTestPump ?: (pairing.snapshot().optString("pump") == "PUMP_${ManualPumpTarget.TEST_SERIAL}")

    /**
     * Forget the pump this watch is paired with, so that another one can be paired.
     *
     * Nothing is destroyed that pairing again could not recreate: the encrypted keys are moved to
     * an archive on the watch, not deleted. What is cleared is everything that describes the old
     * pump and would be wrong about the next one - the choice of pump, the Android bond, the
     * driver's record of the running TBR and of the basal profile, and the pairing record.
     *
     * A pump that broke or was lost has to be replaceable, so this does not wait for anything
     * from the pump. A command whose outcome was never established is given up, and said so.
     *
     * The pump itself goes on listing this watch as its remote until that is deleted in the
     * pump's own Bluetooth menu, or until another device is paired with it.
     *
     * Takes several seconds (the bond removal is waited for); not for the main thread.
     *
     * @return what was done, in the words shown to the owner.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun unpair(): String {
        check(BuildConfig.MANUAL_TARGET)
        val controller = app.aaps.combobench.controller.ControllerHost.get(context)
        // Commands from the phone are kept out for as long as this takes; one that arrives in
        // the meantime then finds no pump and is refused.
        return controller.whilePumpIdle {
            check(!pairing.snapshot().optBoolean("active")) { "Сопряжение ещё идёт" }
            check(!background.isActive()) { "Отложенная проверка ещё запланирована" }
            check(!control.isActive()) { "Служебный сеанс ещё выполняется" }
            check(!therapy.isActive()) { "Сеанс с помпой ещё выполняется" }
            check(!reconnect.isActive()) { "Проверка соединения ещё идёт" }
            check(!controller.isBusy) { "Контроллер сейчас работает с помпой" }
            check(hasSomethingToUnpair()) { "Помпа не привязана" }

            val done = StringBuilder()
            val stamp = System.currentTimeMillis()
            val target = runCatching { target() }.getOrNull()
            val wasTestPump = storedPumpIsTestPump()
            val pump = target?.pump ?: pairing.snapshot().optString("pump").ifEmpty { "PUMP_unknown" }
            val address = pairing.snapshot().optString("address").ifEmpty { target?.address.orEmpty() }

            // The encrypted keys are kept, in case this pump was unpaired by mistake and still
            // holds the watch as its remote.
            val hadKeys = hasPairing()
            for (name in listOf("combo-pairing.enc", "combo-pairing.enc.bak")) {
                val file = File(context.noBackupFilesDir, name)
                if (!file.exists()) continue
                val archived = File(context.noBackupFilesDir, "combo-pairing-archived-$pump-$stamp.enc${if (name.endsWith(".bak")) ".bak" else ""}")
                check(file.renameTo(archived)) { "Не удалось убрать сохранённую привязку" }
            }
            check(!hasPairing()) { "Не удалось убрать сохранённую привязку" }
            done.append(if (hadKeys) "Помпа ${pump.removePrefix("PUMP_")} отвязана." else "Выбор помпы сброшен.")

            // The Android-level bond: with it in place a new pairing with the same pump is refused,
            // and the watch would keep answering that pump's connection attempts.
            if (PairingTarget.matches(address, address)) {
                val removed = runCatching {
                    val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
                    val device = adapter.getRemoteDevice(address)
                    if (device.bondState != android.bluetooth.BluetoothDevice.BOND_NONE) {
                        device.javaClass.getMethod("removeBond").invoke(device)
                        val until = android.os.SystemClock.elapsedRealtime() + 8_000
                        while (device.bondState != android.bluetooth.BluetoothDevice.BOND_NONE && android.os.SystemClock.elapsedRealtime() < until)
                            Thread.sleep(100)
                    }
                    device.bondState == android.bluetooth.BluetoothDevice.BOND_NONE
                }.getOrDefault(false)
                if (!removed) done.append(" Bluetooth-связь с ней снять не удалось: удалите её в настройках Bluetooth часов.")
            }

            // Everything that described the old pump.
            for (name in listOf("manual-target.json", "manual-tbr-state.json", "manual-basal-profile.json"))
                android.util.AtomicFile(File(context.filesDir, name)).delete()
            pairing.forget()
            error = ""

            val givenUp = controller.forgetPump(wasTestPump)
            if (givenUp.isNotEmpty())
                done.append(" Исход последней команды остался невыясненным: проверьте его по самой помпе.")
            if (hadKeys)
                done.append(" На самой помпе часы останутся в списке устройств, пока их не удалить там или не привязать к ней другое устройство.")
            done.toString()
        }
    }

    companion object {
        @Volatile private var instance: ManualPumpRuntime? = null
        fun get(context: Context): ManualPumpRuntime = instance ?: synchronized(this) {
            instance ?: ManualPumpRuntime(context.applicationContext).also { instance = it }
        }
    }
}
