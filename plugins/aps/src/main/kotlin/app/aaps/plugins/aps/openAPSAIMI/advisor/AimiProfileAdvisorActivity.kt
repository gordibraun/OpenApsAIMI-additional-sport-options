package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.R
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView

// ✅ added for chat input
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton

// ✅ added for HTTP chat followups (so you don't have to touch AiCoachingService.kt)
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * =============================================================================
 * AIMI PROFILE ADVISOR ACTIVITY
 * =============================================================================
 * Displays advisor recommendations using localized resources.
 * =============================================================================
 */
class AimiProfileAdvisorActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: app.aaps.core.interfaces.profile.ProfileFunction
    @Inject lateinit var persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer
    @Inject lateinit var preferences: app.aaps.core.keys.interfaces.Preferences

    // NOT injected - created manually to avoid Dagger issues
    private lateinit var advisorService: AimiAdvisorService

    // ✅ CHAT STATE (added)
    private data class ChatMsg(val role: String, val content: String)
    private val chatHistory = mutableListOf<ChatMsg>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pass dependencies to service
        advisorService = AimiAdvisorService(profileFunction, persistenceLayer, preferences)
        title = rh.gs(R.string.aimi_advisor_title)

        // Dark Navy Background
        val bgColor = Color.parseColor("#10141C")
        val cardColor = Color.parseColor("#1E293B")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val scrollView = NestedScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, 24.dpToPx()) // запас снизу на всякий случай
            addView(rootLayout, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scrollView)

        // Loading Indicator
        val loadingText = TextView(this).apply {
            text = "Analyse des données en cours..."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        rootLayout.addView(loadingText)

        // CRITICAL FIX: Load data on IO thread to prevent crash
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val report = advisorService.generateReport(periodDays = 7)
                val context = advisorService.collectContext(7)

                withContext(Dispatchers.Main) {
                    rootLayout.removeView(loadingText)

                    // 1. Header (Title + Score Pill)
                    rootLayout.addView(createDashboardHeader(report))

                    // 2. Metrics Grid (2x2)
                    rootLayout.addView(createMetricsGrid(report.metrics, cardColor))

                    // 3. Section: Observations (Kotlin Rules)
                    rootLayout.addView(createSectionHeader("OBSERVATIONS"))
                    if (report.recommendations.isEmpty()) {
                        // Show "All good" card if needed, or just skip
                    } else {
                        report.recommendations.forEach { rec ->
                            rootLayout.addView(createObservationCard(rec, report.metrics, cardColor))
                        }
                    }

                    // 4. Section: AI Coach (ChatGPT)
                    rootLayout.addView(createSectionHeader("COACH IA"))
                    rootLayout.addView(createCoachCard(context, report, cardColor))

                    // Footer
                    rootLayout.addView(createFooter(report))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingText.text = "Erreur lors de l'analyse :\n${e.localizedMessage}"
                    loadingText.setTextColor(Color.parseColor("#F87171")) // Red
                }
                e.printStackTrace()
            }
        }
    }

    private fun createDashboardHeader(report: AdvisorReport): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 48)

            val infoLayout = LinearLayout(this@AimiProfileAdvisorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            infoLayout.addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = "Rapport Hebdo"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })

            infoLayout.addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = report.metrics.periodLabel
                textSize = 14f
                setTextColor(Color.parseColor("#94A3B8")) // Slate 400
                setPadding(0, 4, 0, 0)
            })

            addView(infoLayout)

            // Score Pill (CardView handles background)
            val pill = CardView(this@AimiProfileAdvisorActivity).apply {
                radius = 50f
                setCardBackgroundColor(Color.parseColor("#0F392B")) // Dark Green bg
                cardElevation = 0f
            }

            val scoreText = TextView(this@AimiProfileAdvisorActivity).apply {
                text = "Score: ${"%.1f".format(report.overallScore)}/10"
                setTextColor(Color.parseColor("#4ADE80")) // Bright Green
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
                setPadding(32, 12, 32, 12)
            }
            pill.addView(scoreText)
            addView(pill)
        }
    }

    private fun createMetricsGrid(metrics: AdvisorMetrics, cardColor: Int): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        // Row 1
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 0, 0, 24)
        }
        row1.addView(createMetricCard("TIR (70-180)", "${(metrics.tir70_180 * 100).roundToInt()}%", Color.parseColor("#4ADE80"), cardColor), paramHalf())
        row1.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(24, 0) })
        row1.addView(createMetricCard("TDD MOYEN", "${metrics.tdd.roundToInt()} U", Color.parseColor("#60A5FA"), cardColor), paramHalf())

        // Row 2
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        row2.addView(createMetricCard("GMI", "${metrics.gmi}%", Color.parseColor("#FACC15"), cardColor), paramHalf())
        row2.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(24, 0) })
        row2.addView(createMetricCard("HYPO < 54", "${(metrics.timeBelow54 * 100).roundToInt()}%", Color.parseColor("#F87171"), cardColor), paramHalf())

        grid.addView(row1)
        grid.addView(row2)
        return grid
    }

    private fun createMetricCard(label: String, value: String, valueColor: Int, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 24f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 48, 16, 48)
        }

        content.addView(TextView(this).apply {
            text = value
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(valueColor)
        })

        content.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8")) // Slate 400
            isAllCaps = true
            setPadding(0, 8, 0, 0)
        })

        card.addView(content)
        return card
    }

    private fun paramHalf(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B")) // Slate 500
            setPadding(4, 0, 0, 24)
            isAllCaps = true
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
        }
    }

    private fun createObservationCard(rec: AimiRecommendation, metrics: AdvisorMetrics, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // Icon Circle
        val iconBg = CardView(this).apply {
            radius = 50f
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#334155")) // Slate 700ish
            layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
        }
        val iconText = TextView(this).apply {
            text = getPriorityEmoji(rec.priority)
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        iconBg.addView(iconText)
        row.addView(iconBg)

        // Text Content
        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
        }

        textLayout.addView(TextView(this).apply {
            text = rh.gs(rec.titleResId)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        val desc = when(rec.descriptionResId) {
            R.string.aimi_adv_rec_hypos_desc -> rh.gs(rec.descriptionResId, (metrics.timeBelow54 * 100).roundToInt(), metrics.severeHypoEvents)
            R.string.aimi_adv_rec_control_desc -> rh.gs(rec.descriptionResId, (metrics.tir70_180 * 100).roundToInt())
            R.string.aimi_adv_rec_hypers_desc -> rh.gs(rec.descriptionResId, (metrics.timeAbove180 * 100).roundToInt())
            R.string.aimi_adv_rec_basal_desc -> rh.gs(rec.descriptionResId, (metrics.basalPercent * 100).roundToInt())
            else -> rh.gs(rec.descriptionResId)
        }

        textLayout.addView(TextView(this).apply {
            text = desc
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8")) // Slate 400
            setLineSpacing(4f, 1.1f)
            setPadding(0, 4, 0, 0)
        })

        row.addView(textLayout)
        card.addView(row)
        return card
    }

    // ✅ Chat UI + follow-up requests
    private fun createCoachCard(context: AdvisorContext, report: AdvisorReport, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 48) }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "✨ ${rh.gs(R.string.aimi_coach_title)}"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#C084FC"))
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        // ✅ chat transcript
        val contentText = TextView(this).apply {
            text = rh.gs(R.string.aimi_coach_loading)
            textSize = 14f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(6f, 1.2f)

            maxLines = Int.MAX_VALUE
            setTextIsSelectable(true)

            // ✅ запас снизу (важно против "обрезается")
            setPadding(0, 0, 0, 96.dpToPx())
        }

        layout.addView(contentText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ✅ input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16.dpToPx(), 0, 0)
        }

        val input = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = "Написать вопрос…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 4
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(24, 18, 24, 18)
        }

        val sendBtn = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(96.dpToPx(), 96.dpToPx()).apply {
                setMargins(16.dpToPx(), 0, 0, 0)
            }
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(Color.parseColor("#334155"))
            setColorFilter(Color.WHITE)
        }

        inputRow.addView(input)
        inputRow.addView(sendBtn)
        layout.addView(inputRow)

        card.addView(layout)

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val apiKey = prefs.getString(app.aaps.core.keys.StringKey.AimiAdvisorOpenAIKey.key, "") ?: ""

        fun renderChat() {
            if (chatHistory.isEmpty()) return
            val sb = StringBuilder()
            for (m in chatHistory) {
                when (m.role) {
                    "user" -> sb.append("🧑 ").append(m.content).append("\n\n")
                    "assistant" -> sb.append("✨ ").append(m.content).append("\n\n")
                    else -> { /* system не печатаем */ }
                }
            }
            contentText.text = sb.toString().trim()
        }

        fun appendAssistant(text: String) {
            chatHistory.add(ChatMsg("assistant", text))
            renderChat()
        }

        fun appendUser(text: String) {
            chatHistory.add(ChatMsg("user", text))
            renderChat()
        }

        // disable input when no key
        if (apiKey.isBlank()) {
            val basicAnalysis = advisorService.generatePlainTextAnalysis(context, report)
            val placeholder = rh.gs(R.string.aimi_coach_placeholder)
            contentText.text = "$basicAnalysis\n\n⚙️ $placeholder"
            input.isEnabled = false
            sendBtn.isEnabled = false
            return card
        }

        // ✅ start chat once
        if (chatHistory.isEmpty()) {
            chatHistory.add(ChatMsg("system",
                                    "Ты помощник по настройке AAPS/OpenAPS AIMI. Отвечай кратко, практично. Не давай опасных медицинских инструкций."
            ))
            contentText.text = rh.gs(R.string.aimi_coach_loading)

            GlobalScope.launch(Dispatchers.Main) {
                try {
                    // Первый ответ — как было раньше (один-shot на базе context/report)
                    val advice = AiCoachingService().fetchAdvice(context, report, apiKey)
                    chatHistory.add(ChatMsg("assistant", advice))
                    renderChat()
                    android.util.Log.d("AIMI_ADVISOR", "adviceLen=${advice.length}, tail=${advice.takeLast(120)}")
                } catch (e: Exception) {
                    contentText.text = rh.gs(R.string.aimi_coach_error)
                }
            }
        } else {
            renderChat()
        }

        // ✅ send follow-up
        sendBtn.setOnClickListener {
            val q = input.text?.toString()?.trim().orEmpty()
            if (q.isBlank()) return@setOnClickListener

            input.setText("")
            appendUser(q)

            sendBtn.isEnabled = false
            input.isEnabled = false

            // маленький "typing"
            chatHistory.add(ChatMsg("assistant", "…"))
            renderChat()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // remove last typing placeholder
                    withContext(Dispatchers.Main) {
                        if (chatHistory.isNotEmpty() && chatHistory.last().role == "assistant" && chatHistory.last().content == "…") {
                            chatHistory.removeAt(chatHistory.size - 1)
                            renderChat()
                        }
                    }

                    val answer = fetchOpenAIChatCompletion(chatHistory, apiKey, maxTokens = 2000)

                    withContext(Dispatchers.Main) {
                        appendAssistant(answer)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendAssistant(rh.gs(R.string.aimi_coach_error))
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        sendBtn.isEnabled = true
                        input.isEnabled = true
                    }
                }
            }
        }

        return card
    }

    // ✅ Minimal OpenAI chat call for follow-ups (keeps your existing AiCoachingService unchanged)
    private fun fetchOpenAIChatCompletion(history: List<ChatMsg>, apiKey: String, maxTokens: Int): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 20000
            readTimeout = 60000
        }

        val messages = JSONArray().apply {
            history.forEach { m ->
                put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
        }

        val body = JSONObject().apply {
            // модель можно поменять на твою, если в AiCoachingService она другая.
            // Я НЕ трогаю твою существующую реализацию — это только для follow-up.
            put("model", "gpt-4o-mini")
            put("messages", messages)
            put("temperature", 0.4)
            put("max_tokens", maxTokens)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (code !in 200..299) {
            throw RuntimeException("OpenAI HTTP $code: $resp")
        }

        val json = JSONObject(resp)
        val choices = json.optJSONArray("choices") ?: JSONArray()
        if (choices.length() == 0) return "Пустой ответ."
        val msg = choices.getJSONObject(0).optJSONObject("message")
        val text = msg?.optString("content", "")?.trim().orEmpty()
        return if (text.isBlank()) "Пустой ответ." else text
    }

    private fun createFooter(report: AdvisorReport): TextView {
        val time = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(report.generatedAt))

        return TextView(this).apply {
            text = "Généré le $time • OpenAPS AIMI"
            textSize = 12f
            setTextColor(Color.parseColor("#475569")) // Slate 600
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 32)
        }
    }

    private fun getScoreColor(severity: AdvisorSeverity): Int = when (severity) {
        AdvisorSeverity.GOOD -> Color.parseColor("#4ADE80")  // Green
        AdvisorSeverity.WARNING -> Color.parseColor("#FACC15")  // Warning
        AdvisorSeverity.CRITICAL -> Color.parseColor("#F87171") // Red
    }

    private fun getPriorityEmoji(priority: RecommendationPriority): String = when (priority) {
        RecommendationPriority.CRITICAL -> "⚠️"
        RecommendationPriority.HIGH -> "📈"
        RecommendationPriority.MEDIUM -> "ℹ️"
        RecommendationPriority.LOW -> "✅"
    }

    // Extension for dp to px
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}