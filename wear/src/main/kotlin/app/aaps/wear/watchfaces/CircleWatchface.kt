@file:Suppress("DEPRECATION")

package app.aaps.wear.watchfaces

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventUpdateSelectedWatchface
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import app.aaps.wear.data.RawDisplayData
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.interaction.utils.Persistence
import app.aaps.wear.watchfaces.utils.WatchfaceViewAdapter.Companion.SelectedWatchFace
import com.ustwo.clockwise.common.WatchFaceTime
import com.ustwo.clockwise.wearable.WatchFace
import dagger.android.AndroidInjection
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import app.aaps.wear.util.WearFileLog
import app.aaps.wear.BuildConfig

class CircleWatchface : WatchFace() {

    private val TAG = "WEAR_FACE"

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var sp: SP
    @Inject lateinit var persistence: Persistence

    private val disposable = CompositeDisposable()

    // Источник данных
    private val rawData = RawDisplayData()

    // Быстрые снапшоты
    private var latestSingleBg: EventData.SingleBg? = null
    private var latestStatus: EventData.Status? = null
    private var latestGraph: EventData.GraphData? = null

    private fun curSingleBg(): EventData.SingleBg = latestSingleBg ?: rawData.singleBg
    private fun curStatus(): EventData.Status = latestStatus ?: rawData.status
    private fun curGraph(): EventData.GraphData = latestGraph ?: rawData.graphData

    // Геометрия
    private val displaySize = Point()
    private lateinit var rect: RectF
    private lateinit var rectDelete: RectF

    // Handler главного потока (для отложенных invalidate и т.п.)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Область тап-кнопки Exercise Mode
    private var exerciseRect: RectF? = null
    private val tmpTextBounds = Rect()

    // Состояние Exercise Mode, присланное телефоном
    private var isExerciseActive: Boolean = false

    // Конфиг UI из версии Саргиса
    private data class UiConfig(
        val bigTextSpNormal: Float = 45f,
        val bigTextSpLarge: Float = 72f,
        val midTextSpNormal: Float = 18f,
        val midTextSpLarge: Float = 28f,
        val smallTextSp: Float = 16f,
        val debugTextSp: Float = 12f,
        val bgOffsetFromCenterSp: Float = 30f,
        val deltaOffsetSp: Float = 28f,
        val agoOffsetSp: Float = 22f,
        val statusOffsetSp: Float = 22f,
        val exOffsetSp: Float = 22f,
        val exTextSizeSp: Float = 12f,
        val exExtraPadSp: Float = 8f,
        val showDevGrid: Boolean = false,
        val showDebugInfo: Boolean = false
    )

    private var uiConfig = UiConfig()

    companion object {
        // значения Мэтью сохраняются
        const val PADDING = 20f
        const val CIRCLE_WIDTH = 10f
        const val BIG_HAND_WIDTH = 16
        const val SMALL_HAND_WIDTH = 8
        const val NEAR = 2
        const val ALWAYS_HIGHLIGHT_SMALL = false
        const val fraction = .5

        // добавлено из Саргиса
        const val EXERCISE_PERCENT = 80
        const val EXERCISE_DURATION_MIN = 30
        const val EXERCISE_TIMESHIFT_MIN = 0
    }

    // Углы/цвет
    private var angleBig = 0f
    private var angleSmall = 0f
    private var ringColor = 0
    private var overlapping = false

    // Paint’ы
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CIRCLE_WIDTH
    }
    private val removePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CIRCLE_WIDTH
    }
    private val textPaintLarge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val textPaintMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val textPaintSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    // История на кольце
    private val bgDataList = ArrayList<EventData.SingleBg>()

    // Отладка задержек
    private var lastInboundElapsed: Long = SystemClock.elapsedRealtime()
    private var lastDrawElapsed: Long = SystemClock.elapsedRealtime()
    private var lastUpdateToInvalidateMs: Long = 0L

    // Метрика по времени прихода событий
    private var tSingleBgMs: Long = 0L
    private var tStatusMs: Long = 0L

    // Запоминаем BG, который уже будил экран
    private var lastWokenBgTimestamp: Long = 0L

    // Двойной тап по центру
    private var sgvTapTime: Long = 0

    // Монитор экрана из версии Саргиса
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    val now = SystemClock.elapsedRealtime()
                    val sinceSingle = if (tSingleBgMs != 0L) (now - tSingleBgMs) else -1
                    val sinceDraw = (now - lastDrawElapsed)
                    logd("SCREEN_ON; +${sinceSingle}ms since SingleBg; +${sinceDraw}ms since lastDraw")

                    // запросить свежие данные с телефона
                    rxBus.send(EventWearToMobile(EventData.ActionResendData("ScreenOn")))

                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    val wl = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "AndroidAPS:CircleWatchface_screenOn"
                    )
                    wl.setReferenceCounted(false)
                    wl.acquire(2000)

                    prepareDrawTime()
                    redrawWithWakeLock("ScreenOn")
                    mainHandler.postDelayed({ invalidate() }, 120L)
                    mainHandler.postDelayed({ invalidate() }, 500L)
                    mainHandler.postDelayed({ invalidate() }, 1000L)
                    mainHandler.postDelayed({ if (wl.isHeld) wl.release() }, 400L)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    logd("SCREEN_OFF")
                }
            }
        }
    }

    private fun logd(msg: String) {
        aapsLogger.debug(LTag.WEAR, "CircleWatchface: $msg")
        Log.d(TAG, msg)
        WearFileLog.d(TAG, msg)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()

        // важно из Мэтью — выбор циферблата
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.CIRCLE.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())

        // файл-лог из Саргиса
        val fileLogEnabled = BuildConfig.DEBUG || sp.getBoolean("wf_filelog", false)
        WearFileLog.init(this, fileLogEnabled)
        logd("FileLog enabled=$fileLogEnabled")

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AndroidAPS:CircleWatchface"
        ).apply {
            acquire(30_000)

            initGeometryAndScales()
            subscribeToBus()
            rawData.updateFromPersistence(persistence)

            // запрос полной посылки
            rxBus.send(EventWearToMobile(EventData.ActionResendData("CircleWatchFace::onCreate")))

            // подписка на события экрана (Саргис)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)

            release()
        }
    }

    override fun onDestroy() {
        disposable.clear()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // Отрисовка
    @Synchronized
    override fun onDraw(canvas: Canvas) {
        aapsLogger.debug(
            LTag.WEAR,
            "CircleWatchface: onDraw(); +${SystemClock.elapsedRealtime() - lastInboundElapsed}ms after invalidate()"
        )
        Log.d(
            TAG,
            "onDraw(); +${SystemClock.elapsedRealtime() - lastInboundElapsed}ms after invalidate()"
        )

        val bgCol = backgroundColor
        canvas.drawColor(bgCol)

        drawTimeRing(canvas)
        drawTexts(canvas)

        lastDrawElapsed = SystemClock.elapsedRealtime()
    }

    // Тик времени — обновляем только время/цвет и перерисовываем
    override fun onTimeChanged(oldTime: WatchFaceTime, newTime: WatchFaceTime) {
        if (oldTime.hasMinuteChanged(newTime)) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AndroidAPS:CircleWatchface_onTimeChanged"
            ).apply {
                acquire(3_000)
                prepareDrawTime()
                invalidate()
                release()
            }
        }
    }

    // Подписки на шину
    private fun subscribeToBus() {
        // Status
        disposable += rxBus
            .toObservable(EventData.Status::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                latestStatus = it
                tStatusMs = SystemClock.elapsedRealtime()
                isExerciseActive = it.exerciseModeActive
                aapsLogger.debug(LTag.WEAR, "CircleWatchface: Rx Status at ${tStatusMs}ms")
                Log.d(TAG, "Rx Status at ${tStatusMs}ms, carbsReq=${it.carbsReq}")
                // поведение Мэтью не трогаем
                rawData.updateFromPersistence(persistence)
                addToWatchSet()
                redrawWithWakeLock("Status")
            }

        // SingleBg — добавляем логику Саргиса (wakeOnNew + дедуп)
        disposable += rxBus
            .toObservable(EventData.SingleBg::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                latestSingleBg = it
                tSingleBgMs = SystemClock.elapsedRealtime()
                aapsLogger.debug(LTag.WEAR, "CircleWatchface: Rx SingleBg at ${tSingleBgMs}ms")
                Log.d(TAG, "Rx SingleBg at ${tSingleBgMs}ms")

                val isNew = it.timeStamp != 0L && it.timeStamp != lastWokenBgTimestamp
                prepareDrawTime()
                if (isNew) {
                    lastWokenBgTimestamp = it.timeStamp
                    wakeAndRedrawNow("SingleBg(new)")
                } else {
                    redrawWithWakeLock("SingleBg(dup)")
                }
            }

        // GraphData
        disposable += rxBus
            .toObservable(EventData.GraphData::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                latestGraph = it
                addToWatchSet()
                logd("Rx GraphData entries=${it.entries.size}")
                redrawWithWakeLock("GraphData")
            }

        // Preferences
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                initTextSizes()
                prepareDrawTime()
                logd("Rx Preferences")
                redrawWithWakeLock("Preferences")
            }
    }

    // 🔋 перерисовка с wakeLock (оригинал Мэтью)
    private fun redrawWithWakeLock(tag: String) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AndroidAPS:CircleWatchface_redraw"
        )
        wl.acquire(2000)
        fastRedraw(tag)
        wl.release()
    }

    // 🔋 отдельная логика Саргиса: будим экран только на новые BG
    private fun wakeAndRedrawNow(tag: String) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager

        var brightWl: PowerManager.WakeLock? = null
        if (!pm.isInteractive) {
            brightWl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AndroidAPS:CircleWatchface_dataWake"
            ).apply {
                setReferenceCounted(false)
                acquire(750)
            }
            logd("$tag: screen was OFF -> wakeAndRedraw")
        }

        val cpuWl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AndroidAPS:CircleWatchface_dataCPU"
        )
        cpuWl.setReferenceCounted(false)
        cpuWl.acquire(2000)

        fastRedraw(tag)
        mainHandler.postDelayed({ invalidate() }, 120L)

        mainHandler.postDelayed({
                                    if (cpuWl.isHeld) cpuWl.release()
                                    brightWl?.let { if (it.isHeld) it.release() }
                                }, 600L)
    }

    private fun fastRedraw(tag: String) {
        val now = SystemClock.elapsedRealtime()
        lastUpdateToInvalidateMs = now - lastInboundElapsed
        lastInboundElapsed = now

        val dFromSingle = if (tSingleBgMs != 0L) (now - tSingleBgMs) else -1
        val dFromStatus = if (tStatusMs != 0L) (now - tStatusMs) else -1

        aapsLogger.debug(
            LTag.WEAR,
            "CircleWatchface: $tag -> invalidate(); ΔinvSincePrev=${lastUpdateToInvalidateMs}ms; +${dFromSingle}ms since SingleBg; +${dFromStatus}ms since Status"
        )
        Log.d(
            TAG,
            "$tag -> invalidate(); ΔinvSincePrev=${lastUpdateToInvalidateMs}ms; +${dFromSingle}ms since SingleBg; +${dFromStatus}ms since Status"
        )

        invalidate()
    }

    // Геометрия/шрифты
    private fun initGeometryAndScales() {
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getSize(displaySize)

        rect = RectF(PADDING, PADDING, displaySize.x - PADDING, displaySize.y - PADDING)
        rectDelete = RectF(
            PADDING - CIRCLE_WIDTH / 2,
            PADDING - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING + CIRCLE_WIDTH / 2
        )

        initTextSizes()
        prepareDrawTime()
        addToWatchSet()
    }

    private fun initTextSizes() {
        val bigNumbers = sp.getBoolean(R.string.key_show_big_numbers, false)

        val big = if (bigNumbers) uiConfig.bigTextSpLarge else uiConfig.bigTextSpNormal
        val mid = if (bigNumbers) uiConfig.midTextSpLarge else uiConfig.midTextSpNormal
        val small = uiConfig.smallTextSp

        textPaintLarge.textSize = spToPx(big)
        textPaintMid.textSize = spToPx(mid)
        textPaintSmall.textSize = spToPx(small)
        debugPaint.textSize = spToPx(uiConfig.debugTextSp)

        val txtCol = textColor
        textPaintLarge.color = txtCol
        textPaintMid.color = txtCol
        textPaintSmall.color = txtCol
        debugPaint.color = txtCol
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    // Расчёт стрелок/цвета кольца
    @Synchronized
    private fun prepareDrawTime() {
        val cal = Calendar.getInstance()
        val hour = cal[Calendar.HOUR_OF_DAY] % 12
        val minute = cal[Calendar.MINUTE]
        angleBig =
            ((hour + minute / 60f) / 12f * 360 - 90 - BIG_HAND_WIDTH / 2f + 360) % 360
        angleSmall =
            (minute / 60f * 360 - 90 - SMALL_HAND_WIDTH / 2f + 360) % 360

        ringColor = when (curSingleBg().sgvLevel.toInt()) {
            -1 -> lowColor
            0 -> inRangeColor
            1 -> highColor
            else -> inRangeColor
        }

        circlePaint.color = ringColor
        circlePaint.strokeWidth = CIRCLE_WIDTH

        removePaint.color = backgroundColor
        removePaint.strokeWidth = CIRCLE_WIDTH * 3

        overlapping = ALWAYS_HIGHLIGHT_SMALL || areOverlapping(
            angleSmall, angleSmall + SMALL_HAND_WIDTH + NEAR,
            angleBig, angleBig + BIG_HAND_WIDTH + NEAR
        )
    }

    private fun areOverlapping(aBegin: Float, aEnd: Float, bBegin: Float, bEnd: Float): Boolean =
        bBegin in aBegin..aEnd ||
            (aBegin <= bBegin && bEnd > 360 && bEnd % 360 > aBegin) ||
            aBegin in bBegin..bEnd ||
            (bBegin <= aBegin && aEnd > 360 && aEnd % 360 > bBegin)

    // Кольцо времени
    private fun drawTimeRing(canvas: Canvas) {
        // основное кольцо
        canvas.drawArc(rect, 0f, 360f, false, circlePaint)

        // вырезы под стрелки
        canvas.drawArc(rectDelete, angleBig, BIG_HAND_WIDTH.toFloat(), false, removePaint)
        canvas.drawArc(rectDelete, angleSmall, SMALL_HAND_WIDTH.toFloat(), false, removePaint)

        if (overlapping) {
            // выделяем малую стрелку
            val strong = Paint(circlePaint).apply { strokeWidth = CIRCLE_WIDTH * 2 }
            canvas.drawArc(
                rect,
                angleSmall,
                SMALL_HAND_WIDTH.toFloat(),
                false,
                strong
            )

            val innerErase = Paint(removePaint).apply { strokeWidth = CIRCLE_WIDTH }
            canvas.drawArc(rect, angleBig, BIG_HAND_WIDTH.toFloat(), false, innerErase)
            canvas.drawArc(rect, angleSmall, SMALL_HAND_WIDTH.toFloat(), false, innerErase)
        }

        // История
        if (sp.getBoolean(R.string.key_show_ring_history, false) && bgDataList.isNotEmpty()) {
            addIndicator(canvas, 100f, Color.LTGRAY)
            addIndicator(canvas, bgDataList.first().low.toFloat(), lowColor)
            addIndicator(canvas, bgDataList.first().high.toFloat(), highColor)

            val soft = sp.getBoolean("softRingHistory", true)
            bgDataList.forEach {
                if (soft) addReadingSoft(canvas, it) else addReading(canvas, it)
            }
        }
    }

    // Тексты по центру: логика Саргиса + оставляем отладочную строку Мэтью
    private fun drawTexts(canvas: Canvas) {
        val cx = displaySize.x / 2f
        val cy = displaySize.y / 2f

        val sbg = curSingleBg()
        val status = curStatus()

        logd("drawTexts: status.carbsReq=${status.carbsReq}")

        // BG
        val bgY = cy - spToPx(uiConfig.bgOffsetFromCenterSp)
        canvas.drawText(sbg.sgvString, cx, bgY, textPaintLarge)

        var currentY = bgY

        // Delta / avgΔ
        val deltaLine = buildString {
            if (sp.getBoolean(R.string.key_show_delta, true)) {
                val detailed =
                    sp.getBoolean(R.string.key_show_detailed_delta, false)
                append(if (detailed) sbg.deltaDetailed else sbg.delta)
                if (sp.getBoolean(R.string.key_show_avg_delta, true)) {
                    append("  ")
                    append(if (detailed) sbg.avgDeltaDetailed else sbg.avgDelta)
                }
            }
        }
        if (deltaLine.isNotEmpty()) {
            currentY += spToPx(uiConfig.deltaOffsetSp)
            canvas.drawText(deltaLine, cx, currentY, textPaintMid)
        }

        // "минут назад" + carbsReq
        if (sp.getBoolean(R.string.key_show_ago, true)) {
            currentY += spToPx(uiConfig.agoOffsetSp)

            val agoText = minutesFrom(sbg.timeStamp)
            val carbsReq = status.carbsReq
            val line = if (carbsReq > 0) {
                "$agoText   Сьеш ${carbsReq} g"
            } else {
                agoText
            }
            canvas.drawText(line, cx, currentY, textPaintSmall)
        }

        // Статус IOB/BGI
        if (sp.getBoolean(R.string.key_show_external_status, true)) {
            currentY += spToPx(uiConfig.statusOffsetSp)
            val detailedIob = sp.getBoolean(R.string.key_show_detailed_iob, false)
            val showBgi = sp.getBoolean(R.string.key_show_bgi, false)
            val iobStr =
                if (detailedIob) "${status.iobSum} ${status.iobDetail}"
                else status.iobSum + getString(R.string.units_short)
            val statLine =
                if (showBgi) "${status.externalStatus}  $iobStr  ${status.bgi}"
                else "${status.externalStatus}  $iobStr"
            canvas.drawText(statLine, cx, currentY, textPaintSmall)
        }

        // Exercise-кнопка
        val oldSmallSize = textPaintSmall.textSize
        val oldColor = textPaintSmall.color
        textPaintSmall.textSize = spToPx(uiConfig.exTextSizeSp)

        currentY += spToPx(uiConfig.exOffsetSp)
        val exText = "Вкл нагр ${EXERCISE_PERCENT}% / ${EXERCISE_DURATION_MIN}m"
        val exY = currentY

        textPaintSmall.getTextBounds(exText, 0, exText.length, tmpTextBounds)
        val halfWidth = tmpTextBounds.width() / 2f
        val extraPad = spToPx(uiConfig.exExtraPadSp)

        exerciseRect = RectF(
            cx - halfWidth - extraPad,
            exY + tmpTextBounds.top - extraPad,
            cx + halfWidth + extraPad,
            exY + tmpTextBounds.bottom + extraPad
        )

        if (isExerciseActive) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (sp.getBoolean(R.string.key_dark, true))
                    Color.argb(120, 0, 200, 0)
                else
                    Color.argb(160, 0, 180, 0)
            }
            canvas.drawRoundRect(
                exerciseRect!!,
                spToPx(6f),
                spToPx(6f),
                bgPaint
            )
            textPaintSmall.color = Color.WHITE
        }

        canvas.drawText(exText, cx, exY, textPaintSmall)

        textPaintSmall.textSize = oldSmallSize
        textPaintSmall.color = oldColor

        // dev-сетка по флагу
        if (uiConfig.showDevGrid) {
            drawDevGrid(canvas, cx, cy)
        }

        // отладочная строка Саргиса — по флагу
        if (uiConfig.showDebugInfo) {
            val sinceInbound = (SystemClock.elapsedRealtime() - lastInboundElapsed) / 1000
            canvas.drawText(
                "lastUpdate: +${sinceInbound}s  Δinv:${lastUpdateToInvalidateMs}ms",
                PADDING, displaySize.y - PADDING, debugPaint
            )
        }

        // и ОТДЕЛЬНО сохраняем старое поведение Мэтью: всегда рисовать debug-строку внизу
        val sinceInbound2 = (SystemClock.elapsedRealtime() - lastInboundElapsed) / 1000
        canvas.drawText(
            "lastUpdate: +${sinceInbound2}s  Δinv:${lastUpdateToInvalidateMs}ms",
            PADDING,
            displaySize.y - PADDING,
            debugPaint
        )
    }

    private fun drawDevGrid(canvas: Canvas, cx: Float, cy: Float) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(cx, 0f, cx, displaySize.y.toFloat(), gridPaint)
        canvas.drawLine(0f, cy, displaySize.x.toFloat(), cy, gridPaint)
    }

    private fun minutesFrom(ts: Long): String =
        if (ts == 0L) "--'"
        else floor((System.currentTimeMillis() - ts) / 60000.0).toInt().toString() + "'"

    private fun addToWatchSet() {
        bgDataList.clear()
        if (!sp.getBoolean(R.string.key_show_ring_history, false)) return
        val threshold =
            (System.currentTimeMillis() - 1000L * 60 * 30).toDouble()
        for (e in curGraph().entries) if (e.timeStamp >= threshold) bgDataList.add(e)
        aapsLogger.debug(LTag.WEAR, "addToWatchSet size=${bgDataList.size}")
    }

    private fun darken(color: Int): Int {
        fun dark(c: Int) = max(c - c * fraction, 0.0).toInt()
        return Color.argb(
            Color.alpha(color),
            dark(Color.red(color)),
            dark(Color.green(color)),
            dark(Color.blue(color))
        )
    }

    private fun addArch(canvas: Canvas, offset: Float, color: Int, size: Float) {
        val rectTemp = RectF(
            PADDING + offset - CIRCLE_WIDTH / 2,
            PADDING + offset - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING - offset + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING - offset + CIRCLE_WIDTH / 2
        )
        val p = Paint().apply { this.color = color }
        canvas.drawArc(rectTemp, 270f, size, true, p)
    }

    private fun addArch(canvas: Canvas, start: Float, offset: Float, color: Int, size: Float) {
        val rectTemp = RectF(
            PADDING + offset - CIRCLE_WIDTH / 2,
            PADDING + offset - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING - offset + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING - offset + CIRCLE_WIDTH / 2
        )
        val p = Paint().apply { this.color = color }
        canvas.drawArc(rectTemp, start + 270, size, true, p)
    }

    private fun addIndicator(canvas: Canvas, bg: Float, color: Int) {
        val converted = bgToAngle(bg) + 270f
        val offset = 9f
        val rectTemp = RectF(
            PADDING + offset - CIRCLE_WIDTH / 2,
            PADDING + offset - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING - offset + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING - offset + CIRCLE_WIDTH / 2
        )
        val p = Paint().apply { this.color = color }
        canvas.drawArc(rectTemp, converted, 2f, true, p)
    }

    private fun bgToAngle(bg: Float): Float =
        if (bg > 100) ((bg - 100f) / 300f * 225f + 135)
        else (bg / 100 * 135)

    private fun addReadingSoft(canvas: Canvas, entry: EventData.SingleBg) {
        val color = if (sp.getBoolean(R.string.key_dark, true))
            Color.DKGRAY
        else
            Color.LTGRAY
        val offsetMultiplier = (displaySize.x / 2f - PADDING) / 12f
        val offset = max(
            1.0,
            ceil((System.currentTimeMillis() - entry.timeStamp) / (1000 * 60 * 5.0))
        ).toFloat()
        val size = bgToAngle(entry.sgv.toFloat())
        addArch(canvas, offset * offsetMultiplier + 10, color, size)
        addArch(canvas, size, offset * offsetMultiplier + 10, backgroundColor, (360 - size))
        addArch(canvas, (offset + .8f) * offsetMultiplier + 10, backgroundColor, 360f)
    }

    private fun addReading(canvas: Canvas, entry: EventData.SingleBg) {
        var color = if (sp.getBoolean(R.string.key_dark, true))
            Color.DKGRAY
        else
            Color.LTGRAY
        var indicatorColor = if (sp.getBoolean(R.string.key_dark, true))
            Color.LTGRAY
        else
            Color.DKGRAY

        var barColor = Color.GRAY
        if (entry.sgv >= entry.high) {
            indicatorColor = highColor
            barColor = darken(highColor)
        } else if (entry.sgv <= entry.low) {
            indicatorColor = lowColor
            barColor = darken(lowColor)
        }

        val offsetMultiplier = (displaySize.x / 2f - PADDING) / 12f
        val offset = max(
            1.0,
            ceil((System.currentTimeMillis() - entry.timeStamp) / (1000 * 60 * 5.0))
        ).toFloat()
        val size = bgToAngle(entry.sgv.toFloat())
        addArch(canvas, offset * offsetMultiplier + 11, barColor, size - 2)
        addArch(canvas, size - 2, offset * offsetMultiplier + 11, indicatorColor, 2f)
        addArch(canvas, size, offset * offsetMultiplier + 11, color, (360f - size))
        addArch(canvas, (offset + .8f) * offsetMultiplier + 11, backgroundColor, 360f)
    }

    // Цвета
    private val lowColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true))
            Color.argb(255, 255, 120, 120)
        else
            Color.argb(255, 255, 80, 80)

    private val inRangeColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true))
            Color.argb(255, 120, 255, 120)
        else
            Color.argb(255, 0, 240, 0)

    private val highColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true))
            Color.argb(255, 255, 255, 120)
        else
            Color.argb(255, 255, 200, 0)

    private val backgroundColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true)) Color.BLACK else Color.WHITE

    private val textColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true)) Color.WHITE else Color.BLACK

    // Тапы: двойной тап Мэтью + Exercise-кнопка Саргиса
    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        if (tapType == TAP_TYPE_TAP) {

            // сначала проверка на Exercise-кнопку
            exerciseRect?.let { rect ->
                if (rect.contains(x.toFloat(), y.toFloat())) {
                    triggerExerciseMode()
                    return
                }
            }

            // затем логика двойного тапа по центру (как у Мэтью)
            val cx = displaySize.x / 2f
            val cy = displaySize.y / 2f
            val dx = x - cx
            val dy = y - cy
            val radiusPx =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    100f,
                    resources.displayMetrics
                )
            if (dx * dx + dy * dy <= radiusPx * radiusPx) {
                if (eventTime - sgvTapTime < 800) {
                    val intent = Intent(this, MainMenuActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                sgvTapTime = eventTime
            }
        }
    }

    // отправка команды Exercise Mode на телефон
    private fun triggerExerciseMode() {
        logd("ExerciseMode button tapped → send ActionExerciseMode")
        rxBus.send(
            EventWearToMobile(
                EventData.ActionExerciseMode(
                    percentage = EXERCISE_PERCENT,
                    duration = EXERCISE_DURATION_MIN,
                    timeShift = EXERCISE_TIMESHIFT_MIN
                )
            )
        )
    }

    override fun getWatchFaceStyle(): WatchFaceStyle =
        WatchFaceStyle.Builder(this)
            .setAcceptsTapEvents(true)
            .build()
}