package app.aaps.plugins.aps.decisiontrace

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.interfaces.aps.DecisionBranch
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.DecisionValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic read-only view tests run in the isolated APS library test package. */
@RunWith(AndroidJUnit4::class)
class DecisionExplanationViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun narrowDark() = render(360, 1f, true, "explanation-small-dark")
    @Test fun largeFontLight() = render(360, 1.5f, false, "explanation-large-font")
    @Test fun wideDark() = render(600, 1f, true, "explanation-wide")

    private fun sample(): TraceRun {
        fun dose(n: Int, title: String, before: String?, after: String, detail: String = "") =
            DecisionTraceStep(n, DecisionStage.SMB, title, detail,
                values = listOf(DecisionValue("SMB", after, before, "Е")))
        val steps = listOf(
            dose(1, "Первичный расчёт", null, "0.4"),
            dose(2, "Объединение двух расчётов микродозы", "0.4", "1.98"),
            dose(3, "Поправка расчёта на еду", "1.98", "2.07"),
            dose(4, "Ослабление с учётом остаточного действия", "2.07", "1.77", "Учтено действие ранее введённого инсулина"),
            dose(5, "Округление до шага помпы", "1.77", "1.75", "Шаг помпы 0,05 Е"),
            dose(6, "Ограничение микродозы", "1.75", "0", "Прогноз ниже цели: 94 при цели 117. Активный инсулин 2,82 Е."),
            DecisionTraceStep(7, DecisionStage.SAFETY, "Выбран вариант по прогнозу", "Проверка снизила базал",
                values = listOf(DecisionValue("Временный базал", "1.1", "2.64", "Е/ч"))),
            DecisionTraceStep(8, DecisionStage.FINAL, "Итог", "", values = listOf(
                DecisionValue("SMB", "0", unit = "Е"), DecisionValue("Базал", "1.1", unit = "Е/ч")),
                branch = DecisionBranch("final", "basal"))
        )
        return TraceRun(100, steps, TraceRunState.CALCULATED, DecisionContext(
            130.0, 10.0, 117.0, 2.82, 16.4, 62.6,
            listOf(130, 140, 150, 160, 163, 156, 144, 130, 119, 112, 105, 99, 94), 0.0, 1.1))
    }

    private fun render(widthDp: Int, fontScale: Float, dark: Boolean, name: String) {
        instrumentation.runOnMainSync {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                this.fontScale = fontScale
                uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), app.aaps.core.ui.R.style.AppTheme)
            val view = DecisionExplanationView(context)
            val run = sample()
            view.render(run)
            var mapOpened = false
            var journalOpened = false
            view.onMap = { mapOpened = true }
            view.onJournal = { journalOpened = true }
            // Render again with a new snapshot so listeners capture the current callbacks.
            view.render(run.copy(id = 101))
            val texts = descendants(view).filterIsInstance<TextView>()
            assertTrue(texts.any { it.text.toString().contains("Почему микродоза стала нулевой") })
            assertTrue(texts.any { it.text.toString().contains("Подтверждения микродозы в этой записи нет") })
            texts.first { it.text.toString().startsWith("Полная карта веток") }.performClick()
            texts.first { it.text.toString().startsWith("Остальные проверки") }.performClick()
            assertTrue(mapOpened)
            assertTrue(journalOpened)
            assertEquals(8, run.steps.size)
            val width = (widthDp * view.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
            for (text in texts) {
                val layout = text.layout ?: continue
                for (line in 0 until layout.lineCount) {
                    assertEquals("Ellipsized: ${text.text}", 0, layout.getEllipsisCount(line))
                    assertTrue("Horizontal clipping: ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 2)
                }
                if (layout.lineCount > 0) assertTrue("Vertical clipping: ${text.text}",
                    layout.getLineBottom(layout.lineCount - 1) <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
            }
            val bitmap = Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) Color.BLACK else Color.WHITE)
            view.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (accent in if (dark) listOf("#CE93D8", "#FFEB3B", "#64B5F6", "#FF8A80")
                else listOf("#7B1FA2", "#806000", "#1565C0", "#B71C1C")) {
                val color = Color.parseColor(accent)
                assertTrue("Missing color $accent", pixels.count { it == color } > 25)
            }
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
