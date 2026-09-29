package app.aaps.plugins.aps.decisiontrace

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Spinner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionBranch
import app.aaps.core.interfaces.aps.DecisionStepKind
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.DecisionValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs in the library's isolated test app, with synthetic events and no APS/pump application. */
@RunWith(AndroidJUnit4::class)
class DecisionFlowPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun narrowDarkScreenHasNoClippedLabels() = render(360, 1f, true, "flow-small-dark")
    @Test fun largeFontLightScreenHasNoClippedLabels() = render(360, 1.3f, false, "flow-large-font")
    @Test fun wideScreenHasNoClippedLabels() = render(600, 1f, true, "flow-wide")
    @Test fun branchMapNarrowDarkHasNoClippedLabels() = render(360, 1f, true, "map-small-dark", true)
    @Test fun branchMapLargeFontLightHasNoClippedLabels() = render(360, 1.3f, false, "map-large-font", true)
    @Test fun branchMapWideHasNoClippedLabels() = render(600, 1f, true, "map-wide", true)

    @Test fun mapDistinguishesLimitFromStopAndDoesNotInventDelivery() {
        instrumentation.runOnMainSync {
            val view = create(1f, true)
            val before = DecisionTraceLive.history.snapshots()
            val labels = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.any { it == "Предел SMB · выбран" })
            assertTrue(labels.any { it == "SMB запрещён · не выбран" })
            assertTrue(labels.any { it.contains("Подтверждения микродозы в этой записи нет") })
            descendants(view).filterIsInstance<TextView>().first { it.text == "Шаги" }.performClick()
            descendants(view).filterIsInstance<TextView>().first { it.text == "Карта" }.performClick()
            assertEquals(before, DecisionTraceLive.history.snapshots())
            view.stop()
        }
    }

    @Test fun playNextPauseAndLiveOnlyMoveRecordedCursor() {
        lateinit var view: DecisionFlowPlayer
        instrumentation.runOnMainSync {
            view = create(1f, true)
            view.start()
            view.refresh()
            val before = DecisionTraceLive.history.snapshots()
            descendants(view).filterIsInstance<TextView>().first { it.text == "Разбор" }.performClick()
            assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString().contains("Шаг 6 /") })
            descendants(view).filterIsInstance<ImageButton>().first { it.contentDescription == "Воспроизвести запись" }.performClick()
            assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString().contains("Шаг 1 /") })
            assertEquals(before, DecisionTraceLive.history.snapshots())
        }
        Thread.sleep(1400)
        instrumentation.runOnMainSync {
            assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString().contains("Шаг 2 /") })
            descendants(view).filterIsInstance<ImageButton>().first { it.contentDescription == "Пауза просмотра" }.performClick()
            descendants(view).filterIsInstance<TextView>().first { it.text == "Сейчас" }.performClick()
            assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString().contains("Шаг 6 /") })
            view.stop()
        }
    }

    @Test fun selectingRecordedRunHighlightsPathEvenWhenFirstStepsHaveNoBranches() {
        instrumentation.runOnMainSync {
            val view = create(1f, true)
            val recorded = (1..6).map { DecisionTraceStep(it, DecisionStage.INPUT, "Входные данные", "") } +
                DecisionTraceStep(7, DecisionStage.SAFETY, "Ранний перелив", "", branch = DecisionBranch("early", "cap")) +
                DecisionTraceStep(8, DecisionStage.FINAL, "Итог", "", branch = DecisionBranch("final", "basal"))
            val id = 1_799_999_999_000L
            DecisionTraceLive.history.merge(id, recorded)
            view.refresh()
            val before = DecisionTraceLive.history.snapshots()
            val spinner = descendants(view).filterIsInstance<Spinner>().single()
            val index = before.indexOfFirst { it.id == id }
            spinner.onItemSelectedListener!!.onItemSelected(spinner, null, index, spinner.adapter.getItemId(index))
            val selected = descendants(view).filterIsInstance<TextView>().first { it.text == "Предел SMB · выбран" }
            assertEquals(Color.parseColor("#FF8A80"), selected.currentTextColor)
            val alternative = descendants(view).filterIsInstance<TextView>().first { it.text == "Без ограничения · не выбран" }
            assertNotEquals(selected.currentTextColor, alternative.currentTextColor)
            descendants(view).filterIsInstance<ImageButton>().first { it.contentDescription == "Воспроизвести запись" }.performClick()
            assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text == "До первой развилки" })
            assertFalse(descendants(view).filterIsInstance<TextView>().any { it.text == "Предел SMB · выбран" })
            descendants(view).filterIsInstance<ImageButton>().first { it.contentDescription == "Весь записанный путь" }.performClick()
            assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text == "Предел SMB · выбран" })
            descendants(view).filterIsInstance<TextView>().first { it.text == "Сейчас" }.performClick()
            descendants(view).filterIsInstance<TextView>().first { it.text == "Разбор" }.performClick()
            assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text == "Предел SMB · выбран" })
            assertEquals(before, DecisionTraceLive.history.snapshots())
            view.stop()
        }
    }

    @Test fun mapBeforeFirstBranchDoesNotClaimBranchesAreMissingFromRun() {
        instrumentation.runOnMainSync {
            val context = create(1f, true).context
            val view = DecisionBranchMapView(context)
            val steps = listOf(DecisionTraceStep(1, DecisionStage.INPUT, "Данные", ""),
                DecisionTraceStep(2, DecisionStage.SAFETY, "Проверка", "", branch = DecisionBranch("early", "cap")))
            view.render(TraceRun(100, steps, TraceRunState.CALCULATED), 1)
            val labels = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.any { it == "До первой развилки" })
            assertFalse(labels.any { it == "В этом расчёте нет записанных ветвлений" })
            assertFalse(labels.any { it == "Предел SMB · выбран" })
        }
    }

    private fun create(fontScale: Float, dark: Boolean): DecisionFlowPlayer {
        val base = instrumentation.targetContext
        val config = Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(base.createConfigurationContext(config), app.aaps.core.ui.R.style.AppTheme)
        val stages = listOf(DecisionStage.INPUT, DecisionStage.SENSITIVITY, DecisionStage.CARBS,
            DecisionStage.FORECAST, DecisionStage.CONSTRAINTS, DecisionStage.DELIVERY)
        val branches = listOf(DecisionBranch("meal_mode", "normal"), DecisionBranch("isf", "minimum"),
            DecisionBranch("food", "declared"), DecisionBranch("early", "cap"),
            DecisionBranch("early_return", "continue"), DecisionBranch("delivery_smb", "requested"))
        val steps = stages.mapIndexed { i, stage ->
            DecisionTraceStep(i + 1, stage, "Тестовый шаг: ${stage.name}",
                "Синтетическая запись для проверки экрана. Команды помпе не отправляются.", DecisionStepKind.CHECKPOINT,
                if (stage == DecisionStage.SENSITIVITY) listOf(DecisionValue("ISF решения", "55.0", "50.0", "мг/дл/Е")) else emptyList(),
                branch = branches[i])
        }
        DecisionTraceLive.history.merge(1_800_000_000_000, steps)
        return DecisionFlowPlayer(themed).apply {
            refresh()
            descendants(this).filterIsInstance<TextView>().first { it.text.toString().startsWith("Полная карта веток") }.performClick()
        }
    }

    private fun render(widthDp: Int, fontScale: Float, dark: Boolean, name: String, map: Boolean = false) {
        instrumentation.runOnMainSync {
            val view = create(fontScale, dark)
            if (!map) {
                descendants(view).filterIsInstance<TextView>().first { it.text == "Шаги" }.performClick()
                descendants(view).filterIsInstance<TextView>().first { it.text == "Разбор" }.performClick()
                descendants(view).filterIsInstance<ImageButton>().first { it.contentDescription == "Воспроизвести запись" }.performClick()
                descendants(view).filterIsInstance<ImageButton>().first { it.contentDescription == "Пауза просмотра" }.performClick()
                descendants(view).filterIsInstance<ImageButton>().first { it.contentDescription == "Следующий шаг" }.performClick()
            }
            val width = (widthDp * view.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
            descendants(view).filterIsInstance<TextView>().filter { visibleInTree(it) }.forEach { text ->
                val layout = text.layout ?: return@forEach
                for (line in 0 until layout.lineCount) {
                    assertEquals("Ellipsized: ${text.text}", 0, layout.getEllipsisCount(line))
                    // Line width includes wrap-ending whitespace; line max measures visible text.
                    assertTrue("Horizontal clipping: ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 2)
                }
                if (layout.lineCount > 0) assertTrue("Vertical clipping: ${text.text}",
                    layout.getLineBottom(layout.lineCount - 1) <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
            }
            val bitmap = Bitmap.createBitmap(width, minOf(view.height, (1600 * view.resources.displayMetrics.density).toInt()), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            view.draw(canvas)
            if (map) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                for (accent in if (dark) listOf("#CE93D8", "#FFEB3B") else listOf("#7B1FA2", "#806000")) {
                    assertTrue("Missing highlighted pixels: $accent", pixels.count { it == Color.parseColor(accent) } > 25)
                }
            }
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            view.stop()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun visibleInTree(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let(::visibleInTree) ?: true)
}
