package app.aaps.plugins.aps.carbmodel

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.aps.databinding.CarbModelFragmentBinding
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CarbAbsorptionModel
import dagger.android.support.DaggerFragment

class CarbModelFragment : DaggerFragment() {

    private var _binding: CarbModelFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        CarbModelFragmentBinding.inflate(inflater, container, false).also {
            _binding = it
        }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.title.paintFlags = binding.title.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.typesTitle.paintFlags = binding.typesTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.currentLogicTitle.paintFlags = binding.currentLogicTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        setHelp(binding.title, "COB-модели", "Эта вкладка показывает три формы всасывания углеводов и помогает увидеть, как разная еда должна входить в прогноз не плоско, а с пиком и хвостом.")
        setHelp(binding.typesTitle, "Типы еды", "Быстрые углеводы дают ранний пик, обычная еда — средний, медленная — поздний и более длинный хвост. Позже этот тип можно будет передавать прямо из Мастера Болюса.")
        setHelp(binding.currentLogicTitle, "Как AIMI использует это сейчас", "Текущий AIMI-прогноз уже использует динамическую COB-кривую вместо плоского размазывания на 180 минут. Пик и длительность зависят от COB и текущего роста.")

        binding.graph.show()
        binding.foodTypes.text = makeInteractiveGlossary(
            buildString {
                CarbAbsorptionModel.FoodType.entries.forEachIndexed { index, type ->
                    if (index > 0) append("\n\n")
                    append("${type.displayName}\n${type.description}\nПик: ${type.peakMinutes.toInt()} мин | Длительность: ${type.absorptionMinutes.toInt()} мин")
                }
            }
        )
        binding.foodTypes.movementMethod = LinkMovementMethod.getInstance()
        binding.foodTypes.highlightColor = Color.TRANSPARENT

        binding.currentLogic.text = makeInteractiveGlossary(
            "Сейчас прогноз AIMI использует динамическую COB-кривую: быстрый рост сдвигает пик раньше и делает вклад углеводов активнее в начале, а более спокойная ситуация растягивает всасывание ближе к обычной еде. Для дополнительной точности позже можно будет добавить выбор типа еды в Мастер Болюса и связать его с UAM и наблюдаемым CI."
        )
        binding.currentLogic.movementMethod = LinkMovementMethod.getInstance()
        binding.currentLogic.highlightColor = Color.TRANSPARENT
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class GlossaryDefinition(val title: String, val body: String)

    private val glossary = linkedMapOf(
        "COB" to GlossaryDefinition("COB", "Carbs On Board — ещё не отыгравшие углеводы, которые система считает остающимися в усвоении."),
        "UAM" to GlossaryDefinition("UAM", "Unannounced Meal — логика распознавания роста, похожего на еду, даже если углеводы не были введены явно."),
        "CI" to GlossaryDefinition("CI", "Carb Impact — наблюдаемый вклад углеводов в изменение глюкозы по реальному поведению сенсора."),
        "Быстрые углеводы" to GlossaryDefinition("Быстрые углеводы", "Сценарий с ранним пиком: сок, сахар, быстрый перекус. Подъём начинается быстро и быстро ослабевает."),
        "Обычная еда" to GlossaryDefinition("Обычная еда", "Средний сценарий всасывания. Это базовая кривая для большинства обычных приёмов пищи."),
        "Медленная еда" to GlossaryDefinition("Медленная еда", "Поздний пик и длинный хвост. Подходит для жирной, белковой или долго усваиваемой еды.")
    )

    private fun setHelp(view: TextView, title: String, message: String) {
        view.setOnClickListener {
            if (context != null) OKDialog.show(requireContext(), title, message)
        }
    }

    private fun makeInteractiveGlossary(content: CharSequence): CharSequence {
        val spannable = SpannableStringBuilder(content)
        val occupied = mutableListOf<IntRange>()
        glossary.keys.sortedByDescending { it.length }.forEach { term ->
            val regex = Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
            regex.findAll(spannable).forEach { match ->
                val range = match.range
                val overlaps = occupied.any { existing -> range.first <= existing.last && existing.first <= range.last }
                if (!overlaps) {
                    val definition = glossary[term] ?: return@forEach
                    val span = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            if (context != null) OKDialog.show(requireContext(), definition.title, definition.body)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = true
                        }
                    }
                    spannable.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    occupied += range
                }
            }
        }
        return spannable
    }
}
