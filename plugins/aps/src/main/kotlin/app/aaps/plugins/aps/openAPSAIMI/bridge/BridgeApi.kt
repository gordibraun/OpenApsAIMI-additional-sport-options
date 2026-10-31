package app.aaps.plugins.aps.openAPSAIMI.bridge

import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bridge on server 91: the owner's reports go up with their data pack, Claude's replies come
 * down. Same host and token as «Еда по фото» (settings of that tab), so nothing new to set up.
 */
@Singleton
class BridgeApi @Inject constructor(private val preferences: Preferences) {

    class Failure(message: String, val retryable: Boolean) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun base(): String = preferences.get(StringKey.FoodApiUrl).trim().trimEnd('/')
    private fun token(): String = preferences.get(StringKey.FoodApiToken).trim()
    fun configured(): Boolean = base().startsWith("http") && token().isNotEmpty()

    private fun request(path: String) = Request.Builder().url("${base()}/v1/bridge/$path").header("X-Food-Token", token())

    fun report(payload: JSONObject): JSONObject = JSONObject(body(request("report").post(payload.toString().toRequestBody(JSON)).build()))
    fun thread(threadId: String, since: String?): JSONArray = JSONArray(body(request("thread/$threadId" + (since?.let { "?since=$it" } ?: "")).get().build()))
    fun threads(n: Int = 10): JSONArray = JSONArray(body(request("threads?n=$n").get().build()))

    private fun body(request: Request): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw Failure("нет связи с сервером: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.isSuccessful     -> return text
                r.code == 401      -> throw Failure("сервер не принял токен", retryable = false)
                r.code in 500..599 -> throw Failure("сервер ответил ${r.code}", retryable = true)
                else               -> throw Failure("сервер отказал: ${r.code} ${text.take(120)}", retryable = false)
            }
        }
    }

    companion object {

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
