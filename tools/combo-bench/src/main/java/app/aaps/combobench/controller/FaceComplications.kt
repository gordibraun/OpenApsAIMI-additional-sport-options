package app.aaps.combobench.controller

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import app.aaps.combobench.BuildConfig
import app.aaps.combobench.ControlLogActivity
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

    private val services = listOf(
        PumpComplicationService::class.java, LinkComplicationService::class.java, ForecastComplicationService::class.java, GraphComplicationService::class.java
    )

    fun requestUpdate(context: Context) {
        if (!BuildConfig.MANUAL_TARGET) return
        for (service in services) runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
        }
    }

    internal fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()

    /** Minutes since [epochMs], counted by the face; [text] wraps it, with `^1` where the minutes go. */
    internal fun sinceMinutes(epochMs: Long, text: String? = null, words: Boolean = false): ComplicationText =
        TimeDifferenceComplicationText.Builder(
            if (words) TimeDifferenceStyle.WORDS_SINGLE_UNIT else TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountUpTimeReference(Instant.ofEpochMilli(epochMs))
        ).setMinimumTimeUnit(TimeUnit.MINUTES).apply { text?.let { setText(it) } }.build()

    internal fun untilMinutes(epochMs: Long): ComplicationText =
        TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(Instant.ofEpochMilli(epochMs)))
            .setMinimumTimeUnit(TimeUnit.MINUTES).build()

    /** Opens the controller's screen: pump, mode, journal. */
    internal fun openController(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 7, Intent(context, ManualPumpSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Opens the log of who led the basal when, and what the watch decided by itself. */
    internal fun openLog(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 8, Intent(context, ControlLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    protected fun short(text: String, title: ComplicationText?, description: String, tap: PendingIntent = FaceComplications.openController(this)): ComplicationData =
        ShortTextComplicationData.Builder(FaceComplications.plain(text), FaceComplications.plain(description))
            .apply { title?.let { setTitle(it) } }
            .setTapAction(tap)
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

/**
 * The pump's state over who leads the basal and for how long: "помпа ✓" above "тел 2 мин" while the
 * phone leads (two minutes since it was heard); "помпа ✓" above "часы 12 мин" while the watch, with
 * the phone away, has been keeping basal by itself for twelve. The first line never changes its
 * meaning. A tap opens the log that explains the changes of lead and the watch's decisions.
 */
class LinkComplicationService : FactsComplicationService() {

    override fun build(facts: FaceFacts?): ComplicationData {
        if (facts == null) return short("—", FaceComplications.plain("связь"), "Состояние связи неизвестно", FaceComplications.openLog(this))
        val pump = when {
            facts.heldPump == null -> "нет помпы"
            facts.pumpReachable    -> "помпа ✓"
            else                   -> "помпа ✗"
        }
        val watchLeads = facts.leader == LeadershipLog.Leader.WATCH || facts.leader == LeadershipLog.Leader.WATCH_OBSERVING
        val who: ComplicationText = when {
            watchLeads && facts.leaderSinceEpochMs > 0L -> FaceComplications.sinceMinutes(facts.leaderSinceEpochMs, "часы ^1", words = true)
            facts.phoneHeardEpochMs > 0L                -> FaceComplications.sinceMinutes(facts.phoneHeardEpochMs, "тел ^1", words = true)
            else                                        -> FaceComplications.plain("тел нет")
        }
        val lead = when (facts.leader) {
            LeadershipLog.Leader.WATCH           -> "Телефона нет: базал ведут часы"
            LeadershipLog.Leader.WATCH_OBSERVING -> "Телефона нет: часы только наблюдают"
            LeadershipLog.Leader.NOBODY          -> "Телефона нет, часы базал не ведут"
            LeadershipLog.Leader.PHONE           -> "Ведёт телефон"
        }
        val pumpState = when {
            facts.heldPump == null -> "помпа к часам не привязана"
            facts.pumpReachable    -> "помпа отвечает"
            else                   -> "помпа не отвечает"
        }
        return ShortTextComplicationData.Builder(FaceComplications.plain(pump), FaceComplications.plain("$lead; $pumpState"))
            .setTitle(who)
            .setTapAction(FaceComplications.openLog(this))
            .build()
    }

    override fun preview(): ComplicationData = short("помпа ✓", FaceComplications.plain("тел 2 мин"), "Телефон и помпа", FaceComplications.openLog(this))
}

/**
 * The graph as a small image: the last hour of readings, the three-hour forecast, insulin on board
 * and the trend arrow; see [FaceGraph]. A tap opens the log.
 */
class GraphComplicationService : FactsComplicationService() {

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(if (request.complicationType == ComplicationType.SMALL_IMAGE) build(FaceComplications.facts(this)) else null)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SMALL_IMAGE) preview() else null

    override fun build(facts: FaceFacts?): ComplicationData {
        val bitmap = FaceGraph.draw(facts, System.currentTimeMillis())
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build(),
            FaceComplications.plain(FaceGraph.describe(facts))
        ).setTapAction(FaceComplications.openLog(this)).build()
    }

    override fun preview(): ComplicationData = build(null)
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
            .setTapAction(FaceComplications.openLog(this))
            .build()
    }

    override fun preview(): ComplicationData = short("95→112", FaceComplications.plain("тел"), "Прогноз")
}
