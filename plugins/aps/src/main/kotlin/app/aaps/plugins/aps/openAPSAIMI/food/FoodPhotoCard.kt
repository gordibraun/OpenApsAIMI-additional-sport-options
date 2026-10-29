package app.aaps.plugins.aps.openAPSAIMI.food

import app.aaps.core.interfaces.aps.CarbSplit
import kotlin.math.roundToInt

/**
 * What one photographed plate says on the screen, worked out without any Android: the tests read
 * these words to check that a glance is enough. The fragment only draws them.
 */
object FoodPhotoCard {

    enum class Tone { DIM, ACCENT, GOOD, WARN }

    enum class Action { EAT, CORRECT, DECLINE, UNDO, RETRY, DELETE }

    data class View(
        /** «12:40 · обед» */
        val header: String,
        /** The state in two words, on a coloured chip. */
        val chip: String,
        val chipTone: Tone,
        /** The one line that matters: the grams, or why there are none yet. */
        val headline: String,
        /** Under the headline: the range, the estimate the owner overrode, when it was entered. */
        val subline: String?,
        /** One line per dish. */
        val items: List<String>,
        /** Fat, protein and the absorption type in one line. */
        val composition: String?,
        /** The server's one-sentence hint on how to enter it. */
        val advice: String?,
        /** Something went wrong and the owner should know. */
        val problem: String?,
        /** What the owner can do now, the first being the main one. */
        val actions: List<Action>,
        /** The grams the main action would enter. */
        val gramsToEnter: Int?
    )

    fun of(entry: FoodPhotoEntry, sending: Boolean, timeString: (Long) -> String): View {
        val header = listOfNotNull(timeString(entry.atEpochMs), entry.caption.takeIf { it.isNotBlank() }?.let { "«$it»" }).joinToString(" · ")
        val estimate = entry.estimate
        val own = entry.finalGrams
        val grams = entry.gramsToEnter?.roundToInt()
        val items = estimate?.items.orEmpty().map { it ->
            buildString {
                append("• ").append(it.name)
                it.portion?.let { p -> append(", ").append(p) }
                it.carbsG?.let { g -> append(" — ").append(g.roundToInt()).append(" г") }
                if ((it.carbsG ?: 0.0) > 0) append(", ").append(CarbSplit.label(it.absorption ?: estimate?.absorption ?: CarbSplit.BALANCED))
            }
        }
        val split = entry.splitToEnter
        val splitLine = split?.takeIf { it.parts().size > 1 }?.let { "по типу: " + it.describe() }
        val composition = estimate?.let { e ->
            listOfNotNull(
                absorptionWord(e.absorption),
                e.fatG?.let { "жиры ~${it.roundToInt()} г" },
                e.proteinG?.let { "белки ~${it.roundToInt()} г" },
                confidenceWord(e.confidence)
            ).joinToString(" · ").ifEmpty { null }
        }
        val estimateLine = estimate?.let { e -> "≈ ${e.carbsG.roundToInt()} г углеводов" }
        val rangeLine = estimate?.let { e -> if (e.rangeLowG != null && e.rangeHighG != null) "от ${e.rangeLowG.roundToInt()} до ${e.rangeHighG.roundToInt()} г" else null }

        return when (entry.status) {
            FoodPhotoEntry.Status.QUEUED    -> View(
                header = header,
                chip = if (sending) "Оцениваю…" else "Ждёт сети",
                chipTone = if (sending) Tone.ACCENT else Tone.DIM,
                headline = if (sending) "Смотрю на тарелку, обычно полминуты" else "Оценка придёт сама, как появится интернет",
                subline = own?.let { "Вы указали ${it.roundToInt()} г — можно ввести, не дожидаясь" },
                items = emptyList(), composition = null, advice = null,
                problem = entry.error,
                actions = listOfNotNull(if (own != null) Action.EAT else null, Action.CORRECT, Action.DECLINE),
                gramsToEnter = grams
            )

            FoodPhotoEntry.Status.ESTIMATED -> View(
                header = header,
                chip = if (own != null) "Вы поправили" else "Оценено",
                chipTone = Tone.ACCENT,
                headline = if (own != null) "${own.roundToInt()} г углеводов — ваша цифра" else estimateLine ?: "Углеводы не названы — укажите сами",
                subline = listOfNotNull(if (own != null) estimateLine?.let { "оценка была $it" } else rangeLine, splitLine).joinToString(" · ").ifEmpty { null },
                items = items, composition = composition, advice = estimate?.advice,
                problem = entry.error,
                actions = listOfNotNull(if (grams != null) Action.EAT else null, Action.CORRECT, Action.DECLINE),
                gramsToEnter = grams
            )

            FoodPhotoEntry.Status.EATEN     -> View(
                header = header,
                chip = "Съедено",
                chipTone = Tone.GOOD,
                headline = grams?.let { "$it г отправлены в калькулятор" } ?: "Съедено без граммов",
                subline = listOfNotNull(
                    entry.decidedAtEpochMs?.let { "в ${timeString(it)}" },
                    split?.takeIf { it.parts().size > 1 }?.describe(),
                    if (own != null && estimate != null && own.roundToInt() != estimate.carbsG.roundToInt()) "оценка была ≈ ${estimate.carbsG.roundToInt()} г" else null,
                    "в заметке болюса: фото ${entry.shortId}"
                ).joinToString(", "),
                items = items, composition = composition, advice = null,
                problem = entry.error,
                actions = listOf(Action.UNDO),
                gramsToEnter = grams
            )

            FoodPhotoEntry.Status.DECLINED  -> View(
                header = header,
                chip = "Не ел",
                chipTone = Tone.DIM,
                headline = "Фото без еды: в расчёты не попадёт",
                subline = estimateLine?.let { "оценка была $it" },
                items = emptyList(), composition = null, advice = null,
                problem = null,
                actions = listOf(Action.UNDO, Action.DELETE),
                gramsToEnter = grams
            )

            FoodPhotoEntry.Status.ERROR     -> View(
                header = header,
                chip = "Не вышло",
                chipTone = Tone.WARN,
                headline = "Сервер не оценил это фото",
                subline = own?.let { "Вы указали ${it.roundToInt()} г — можно ввести" } ?: "Можно повторить или указать граммы самому",
                items = emptyList(), composition = null, advice = null,
                problem = entry.error,
                actions = listOfNotNull(if (own != null) Action.EAT else null, Action.RETRY, Action.CORRECT, Action.DELETE),
                gramsToEnter = grams
            )
        }
    }

    fun actionLabel(action: Action, gramsToEnter: Int?): String = when (action) {
        Action.EAT     -> gramsToEnter?.let { "✅ Ем: $it г в калькулятор" } ?: "✅ Ем"
        Action.CORRECT -> "✏️ Поправить граммы"
        Action.DECLINE -> "✖ Не ем"
        Action.UNDO    -> "↩ Отменить"
        Action.RETRY   -> "↻ Повторить"
        Action.DELETE  -> "🗑 Удалить"
    }

    /** The screen when there is nothing yet. */
    const val EMPTY = "Сфотографируйте тарелку: примерно через полминуты здесь появится оценка углеводов.\n\nФото — ещё не еда. Пока вы не нажмёте «Ем», в калькулятор ничего не попадёт."

    const val INTRO = "Фото → оценка углеводов → «Ем» подставит граммы в калькулятор болюса."

    private fun absorptionWord(absorption: String?) = when (absorption) {
        "fast"     -> "быстрая еда"
        "balanced" -> "обычная еда"
        "slow"     -> "медленная еда (жир/белок)"
        null       -> null
        else       -> absorption
    }

    private fun confidenceWord(confidence: String?) = when (confidence) {
        "low"    -> "уверенность низкая"
        "medium" -> "уверенность средняя"
        "high"   -> "уверенность высокая"
        else     -> null
    }
}
