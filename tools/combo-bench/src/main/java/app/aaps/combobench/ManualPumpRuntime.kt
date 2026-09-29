package app.aaps.combobench

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Local manual pairing/diagnostics. No Wear messages, grants or therapy commands. */
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

    fun target(): ManualPumpTarget? = if (files.exists("manual-target.json")) {
        val saved = files.read("manual-target.json")
        ManualPumpTarget(saved.getString("serial"), saved.optString("address"))
    } else null

    fun configure(serial: String, address: String) {
        check(BuildConfig.MANUAL_TARGET)
        check(!background.isActive()) { "Отложенная проверка уже запланирована" }
        check(!control.isBlocked()) { "Служебный сеанс не завершён" }
        check(!therapy.isActive()) { "Сеанс драйвера ещё выполняется" }
        check(!pairing.snapshot().optBoolean("active")) { "Сопряжение уже идёт" }
        check(!reconnect.isActive()) { "Проверка соединения уже идёт" }
        val target = ManualPumpTarget(serial, address)
        val previous = target()
        check(!hasPairing() || (previous?.serial == target.serial && previous.address == target.address)) {
            "Сохранённая привязка не будет перезаписана"
        }
        files.write("manual-target.json", JSONObject().put("serial", target.serial).put("address", target.address ?: ""))
        error = ""
    }

    fun start(offBody: Boolean, noOtherController: Boolean) {
        try {
            check(BuildConfig.MANUAL_TARGET && offBody && noOtherController) { "Подтвердите условия тестового стенда" }
            check(!background.isActive()) { "Отложенная проверка уже запланирована" }
            check(!control.isBlocked()) { "Служебный сеанс не завершён" }
            check(!therapy.isActive()) { "Сеанс драйвера ещё выполняется" }
            check(!reconnect.isActive()) { "Проверка соединения уже идёт" }
            check(pairing.snapshot().optString("stage") != "INTERRUPTED" || canRestartPreparation()) {
                "Прошлый сеанс прерван; сначала нужно проверить его результат"
            }
            check(pairing.snapshot().optBoolean("cleanupKnown", true)) { "Результат прошлого сеанса неизвестен; повтор заблокирован" }
            val target = checkNotNull(target()) { "Сначала укажите тестовую помпу" }
            check(!hasPairing()) { "Привязка уже сохранена. Новое сопряжение не требуется" }
            error = ""
            pairing.start(target.address, target.pump) { settled ->
                if (!settled) error = "Результат неизвестен; повторное сопряжение заблокировано"
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

    companion object {
        @Volatile private var instance: ManualPumpRuntime? = null
        fun get(context: Context): ManualPumpRuntime = instance ?: synchronized(this) {
            instance ?: ManualPumpRuntime(context.applicationContext).also { instance = it }
        }
    }
}
