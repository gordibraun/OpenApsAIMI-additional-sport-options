// основной промпт задача для нейросети здесь
package app.aaps.plugins.aps.openAPSAIMI.advisor

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * =============================================================================
 * AI COACHING SERVICE
 * =============================================================================
 *
 * Interacts with OpenAI API to generate natural language coaching advice.
 * Uses robust HttpURLConnection (zero dependency).
 * =============================================================================
 */
class AiCoachingService {

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o"
        private const val TAG = "AiCoachingService"
    }

    /**
     * Fetch advice asynchronously.
     * @param context The advisor context (metrics, profile, etc.)
     * @param report The generated rules-based report (with actions)
     * @param apiKey The user's OpenAI API Key
     */
    suspend fun fetchAdvice(
        context: AdvisorContext,
        report: AdvisorReport,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "API Key manquante. Veuillez configurer votre clé OpenAI."

        try {
            val keysDump =
                flattenJsonKeys(JSONObject(context.apsSettings.json))
                    .joinToString("\n")

            logLong("AIMI_KEYS", keysDump)

            // 2️⃣ ЛОГ: КЛЮЧ = ЗНАЧЕНИЕ (РЕАЛЬНЫЕ ЦИФРЫ)
            val apsObj = JSONObject(context.apsSettings.json)
            val valuesDump =
                flattenJsonKeyValues(apsObj)
                    .joinToString("\n")
            logLong("AIMI_VALUES", valuesDump)

            val prompt = buildPrompt(context, report)
            logLong("AIMI_PROMPT", "===== FULL PROMPT SENT TO AI =====\n$prompt")
            Log.d("AIMI_PROMPT", "PROMPT length=${prompt.length}")
            val jsonBody = buildJsonBody(prompt)
            val jsonStr = jsonBody.toString()
            Log.d(TAG, "OPENAI JSON length=${jsonStr.length}")
            logLong(TAG, "===== OPENAI REQUEST JSON =====\n$jsonStr")
            val url = URL(OPENAI_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 15000 // 15s
                readTimeout = 30000    // 30s
            }

            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                val responseStr = response.toString()

                // ✅ сырой JSON-ответ OpenAI (может быть большой)
                Log.d("AIMI_RESPONSE", "RESPONSE JSON length=${responseStr.length}")
                logLong("AIMI_RESPONSE", "===== OPENAI RAW RESPONSE =====\n$responseStr")

                // ✅ распарсенный текст
                val advice = parseResponse(responseStr)
                Log.d("AIMI_ADVISOR", "adviceLen=${advice.length}, tail=${advice.takeLast(30)}")
                logLong("AIMI_ADVISOR", "===== AI ADVICE TEXT =====\n$advice")

                return@withContext advice
            } else {
                return@withContext "Erreur API ($responseCode). Veuillez vérifier votre clé ou votre connexion."
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Erreur de connexion : ${e.localizedMessage}"
        }
    }

    private fun flattenJsonKeys(obj: JSONObject, prefix: String = ""): List<String> {
        val out = mutableListOf<String>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            val v = obj.opt(k)
            when (v) {
                is JSONObject -> out += flattenJsonKeys(v, path)
                else -> out += path
            }
        }
        return out.sorted()
    }

    private fun flattenJsonKeyValues(
        obj: JSONObject,
        prefix: String = "",
        maskKeys: Set<String> = setOf("global.aimi_advisor_openai_key")
    ): List<String> {
        val out = mutableListOf<String>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            val v = obj.opt(k)

            when (v) {
                is JSONObject -> out += flattenJsonKeyValues(v, path, maskKeys)
                is JSONArray -> out += "$path=[len=${v.length()}]"
                else -> {
                    val valueStr = if (maskKeys.contains(path)) "***MASKED***" else (v?.toString() ?: "null")
                    out += "$path=$valueStr"
                }
            }
        }
        return out.sorted()
    }

    private fun logLong(tag: String, msg: String, chunkSize: Int = 3000) {
        var i = 0
        while (i < msg.length) {
            val end = minOf(i + chunkSize, msg.length)
            android.util.Log.d(tag, msg.substring(i, end))
            i = end
        }
    }

    /**
     * Construct the prompt for the LLM.
     */
    private fun buildPrompt(ctx: AdvisorContext, report: AdvisorReport): String {
        val cgm = ctx.cgm24h
        val interval = cgm.intervalMin.coerceAtLeast(1)
        val pointsPerHour = (60 / interval).coerceAtLeast(1)
        val last8h = cgm.valuesMgDl.takeLast(pointsPerHour * 8)

        val lastValue = cgm.valuesMgDl.lastOrNull()
        val min8h = last8h.minOrNull()
        val max8h = last8h.maxOrNull()

        // NEW: insulin / activity snapshots (may be null)
        val ins = ctx.insulin24h
        val steps = ctx.steps24h
        val stepsSummary = steps?.let {
            val last8hSteps = it.values.takeLast(pointsPerHour * 8)
            val stepsTotal24h = it.values.sum()
            val stepsTotal8h = last8hSteps.sum()
            "steps24h: intervalMin=${it.intervalMin}, total24h=$stepsTotal24h, total8h=$stepsTotal8h, last8h=$last8hSteps"
        } ?: "steps24h: null"

        val insulinSummary = ins?.let {
            val tb = it.tempBasals.takeLast(10)
            val smbs = it.smbs.takeLast(10)
            val bol = it.boluses.takeLast(10)
            """
            insulin24h:
            - totalU=${it.totalU}, basalU=${it.basalU}, bolusU=${it.bolusU}, smbU=${it.smbU}
            - iobNow=${it.iobNow}, cobNow=${it.cobNow}
            - tempBasals(last10)=$tb
            - smbs(last10)=$smbs
            - boluses(last10)=$bol
            """.trimIndent()
        } ?: "insulin24h: null"

        return """
ЗАДАЧА:
Разобрать, почему глюкоза систематически отклоняется примерно на ±20 mg/dL от цели.
Дать инженерные гипотезы, проверки и безопасные действия.

ДАННЫЕ:
- Target: ${ctx.profile.targetBg} mg/dL
- Mean BG (7d): ${ctx.metrics.meanBg.roundToInt()} mg/dL
- Δ от Target: ${(ctx.metrics.meanBg - ctx.profile.targetBg).roundToInt()} mg/dL
- TIR 70–180: ${(ctx.metrics.tir70_180 * 100).roundToInt()}%
- Hypo <70: ${(ctx.metrics.timeBelow70 * 100).roundToInt()}%
- Hyper >180: ${(ctx.metrics.timeAbove180 * 100).roundToInt()}%
- CGM last8h: last=$lastValue, min=$min8h, max=$max8h, values=$last8h
- $insulinSummary
- $stepsSummary
- Profile: basalNight=${ctx.profile.nightBasal}, IC=${ctx.profile.icRatio}, ISF=${ctx.profile.isf}
- AIMI prefs: MaxSMB=${ctx.prefs.maxSmb}, LunchFactor=${ctx.prefs.lunchFactor}, AutodriveMaxBasal=${ctx.prefs.autodriveMaxBasal}

JSON SETTINGS (SOURCE OF TRUTH):
${ctx.apsSettings.json}

RULES-BASED REPORT:
${report.toString()}

ФОРМАТ ОТВЕТА:
1) КОРОТКО: что идёт не так
2) ТОП-5 ГИПОТЕЗ
   - причина
   - какие параметры задействованы
   - confidence: LOW/MEDIUM/HIGH
3) ЧТО ПРОВЕРИТЬ В ЛОГАХ (конкретно)
4) 2–3 БЕЗОПАСНЫХ ЭКСПЕРИМЕНТА (малый шаг + критерий отката)

НЕ ПОВТОРЯЙ входные данные.

""".trimIndent()
    }

    /**
     * Build JSON body for OpenAI API.
     */
    private fun buildJsonBody(prompt: String): JSONObject {
        val root = JSONObject()
        root.put("model", MODEL)

        val messages = JSONArray()

        val systemMsg = JSONObject()
        systemMsg.put("role", "system") // ✅ добавили role
        systemMsg.put(
            "content", """
Ты работаешь в режиме ГЛУБОКОГО ИССЛЕДОВАНИЯ (Deep Technical Audit).

ОПРЕДЕЛЕНИЕ РЕЖИМА:
нужно понять где что то идет не так

ТРЕБОВАНИЯ К ВЫВОДАМ:
- Не задавай вопросы
---
Ты — технический аудитор настроек AIMI / AndroidAPS (замкнутый контур).
Цель: дать инженерный аудит конфигурации, а не общие советы по диабету.

ПРАВИЛА ОТВЕТА:
обязательно найди точную причину проблемы. Помни что тебе переданы все данные для анализа так что именно порекомендуй мне как поменять параметры.
Текст ответа на русском, ключи JSON допускаются как есть.
            """.trimIndent()
        )
        messages.put(systemMsg)

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", prompt)
        messages.put(userMsg)

        root.put("messages", messages)
        root.put("temperature", 0.3)
        root.put("max_tokens", 3000)

        return root
    }

    /**
     * Parse OpenAI JSON response.
     */
    private fun parseResponse(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val choices = root.getJSONArray("choices")
            if (choices.length() > 0) {
                choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } else {
                "Pas de réponse du coach."
            }
        } catch (e: Exception) {
            "Erreur de lecture de la réponse AI."
        }
    }
}