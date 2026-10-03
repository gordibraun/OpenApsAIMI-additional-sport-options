package app.aaps.combobench.controller

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import app.aaps.combobench.BuildConfig
import app.aaps.combobench.ManualPumpSetupActivity
import app.aaps.pump.combowatch.executor.AutonomyPolicy
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The controller's three complications for the watch face: the pump, the link, the forecast.
 *
 * Wear OS carries them to whatever face the owner uses; the face only has to give them a slot.
 * They are pushed on every event that can change them (a result, a heartbeat, a decision) and
 * re-read by the system every five minutes besides; the ages and the remaining time in them are
 * live texts, counted by the face itself.
 */
object FaceComplications {

    private val services = listOf(PumpComplicationService::class.java, LinkComplicationService::class.java, ForecastComplicationService::class.java)

    fun requestUpdate(context: Context) {
        if (!BuildConfig.MANUAL_TARGET) return
        for (service in services) runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
        }
    }

    internal fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()

    internal fun sinceMinutes(epochMs: Long): ComplicationText =
        TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(epochMs)))
            .setMinimumTimeUnit(TimeUnit.MINUTES).build()

    internal fun untilMinutes(epochMs: Long): ComplicationText =
        TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(Instant.ofEpochMilli(epochMs)))
            .setMinimumTimeUnit(TimeUnit.MINUTES).build()

    /** Opens the controller's screen: pump, mode, journal. */
    internal fun openController(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 7, Intent(context, ManualPumpSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun facts(context: Context): FaceFacts? = runCatching { ControllerHost.get(context).faceFacts() }.getOrNull()
}

/** Base for the three: the face asks, the controller's facts answer. */
abstract class FactsComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(if (request.complicationType == ComplicationType.SHORT_TEXT) build(FaceComplications.facts(this)) else null)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) preview() else null

    abstract fun build(facts: FaceFacts?): ComplicationData
    abstract fun preview(): ComplicationData

    protected fun short(text: String, title: ComplicationText?, description: String): ComplicationData =
        ShortTextComplicationData.Builder(FaceComplications.plain(text), FaceComplications.plain(description))
            .apply { title?.let { setTitle(it) } }
            .setTapAction(FaceComplications.openController(this))
            .build()
}

/** "120%" over the minutes left; "базал" when the pump runs its profile; "—" when the pump is unknown. */
class PumpComplicationService : FactsComplicationService() {

    override fun build(facts: FaceFacts?): ComplicationData {
        if (facts?.heldPump == null) return short("—", FaceComplications.plain("помпа"), "Помпа к часам не привязана")
        val tbr = facts.tbr?.takeIf { it.endsAtEpochMs > System.currentTimeMillis() }
        return when {
            tbr != null -> short("${tbr.percent}%", FaceComplications.untilMinutes(tbr.endsAtEpochMs), "Временный базал ${tbr.percent} %")
            facts.pumpReadAtEpochMs != null -> short("базал", facts.reservoirUnits?.let { FaceComplications.plain("$it Ед") }, "Профильный базал")
            else -> short("—", FaceComplications.plain("помпа"), "Помпа ещё не читалась")
        }
    }

    override fun preview(): ComplicationData = short("120%", FaceComplications.plain("12м"), "Временный базал")
}

/** How long since the phone was heard, and whether the pump answered - or "ОДНИ" when the watch is on its own. */
class LinkComplicationService : FactsComplicationService() {

    override fun build(facts: FaceFacts?): ComplicationData {
        if (facts == null) return short("—", FaceComplications.plain("связь"), "Состояние связи неизвестно")
        val phone: ComplicationText = if (facts.phoneHeardEpochMs > 0L) FaceComplications.sinceMinutes(facts.phoneHeardEpochMs) else FaceComplications.plain("нет")
        val title = when {
            facts.alone                         -> "ОДНИ" + if (facts.mode == AutonomyPolicy.Mode.ACTIVE) " ✓" else ""
            facts.heldPump == null              -> "нет помпы"
            facts.pumpReachable                 -> "помпа ✓"
            else                                -> "помпа ✗"
        }
        val text = if (facts.alone) "тел" else "тел"
        return ShortTextComplicationData.Builder(FaceComplications.plain(text), FaceComplications.plain("Телефон и помпа: $title"))
            .setTitle(if (facts.alone) FaceComplications.plain(title) else phone)
            .setTapAction(FaceComplications.openController(this))
            .build().let { if (facts.alone) it else withSecondLine(it, title) }
    }

    /** SHORT_TEXT has one text and one title; the phone's age goes in the title, the pump's state in the text. */
    private fun withSecondLine(data: ComplicationData, pumpState: String): ComplicationData =
        ShortTextComplicationData.Builder(FaceComplications.plain(pumpState), (data as ShortTextComplicationData).contentDescription ?: FaceComplications.plain(""))
            .setTitle(data.title ?: FaceComplications.plain(""))
            .setTapAction(FaceComplications.openController(this))
            .build()

    override fun preview(): ComplicationData = short("помпа ✓", FaceComplications.plain("2м"), "Телефон и помпа")
}

/** The four-hour forecast: lowest and last value, and whose forecast it is. */
class ForecastComplicationService : FactsComplicationService() {

    override fun build(facts: FaceFacts?): ComplicationData {
        val forecast = facts?.forecast ?: return short("—", FaceComplications.plain("прогноз"), "Прогноза нет")
        val whose = if (forecast.byWatch) "часы" else "тел"
        return ShortTextComplicationData.Builder(
            FaceComplications.plain("${forecast.minMgdl}→${forecast.endMgdl}"),
            FaceComplications.plain("Прогноз: минимум ${forecast.minMgdl}, через четыре часа ${forecast.endMgdl}; расчёт: $whose")
        )
            .setTitle(FaceComplications.plain(whose))
            .setTapAction(FaceComplications.openController(this))
            .build()
    }

    override fun preview(): ComplicationData = short("95→112", FaceComplications.plain("тел"), "Прогноз")
}
