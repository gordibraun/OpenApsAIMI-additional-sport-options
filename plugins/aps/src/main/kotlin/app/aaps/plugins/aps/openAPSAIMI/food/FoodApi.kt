package app.aaps.plugins.aps.openAPSAIMI.food

import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's side of the food server (food_api.py behind nginx on the owner's server): a photo up,
 * the estimate back; the owner's decision up. Plain HTTPS with a public certificate, so it works on
 * any network without a VPN. The token lives in the preferences, pasted from the bot's /token.
 */
@Singleton
class FoodApi @Inject constructor(private val preferences: Preferences) {

    class Failure(message: String, val retryable: Boolean) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun base(): String = preferences.get(StringKey.FoodApiUrl).trim().trimEnd('/')
    private fun token(): String = preferences.get(StringKey.FoodApiToken).trim()

    fun configured(): Boolean = base().startsWith("http") && token().isNotEmpty()

    /** The server's record for this photo: {id, estimate{…}, text}. */
    fun estimate(entry: FoodPhotoEntry): JSONObject {
        check(configured()) { "адрес или токен сервера не заданы в настройках" }
        val file = File(entry.photoPath)
        if (!file.exists()) throw Failure("фото пропало с телефона", retryable = false)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "photo.jpg", file.asRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("caption", entry.caption)
            .addFormDataPart("client_id", entry.id)
            .build()
        return call(Request.Builder().url("${base()}/v1/estimate").header("X-Food-Token", token()).post(body).build())
    }

    /** Tell the server what the owner decided. [decision] is eaten, declined or undecided (a corrected number, an undo). */
    fun decision(serverId: String, decision: String, grams: Double?, aapsNote: String?): JSONObject {
        check(configured()) { "адрес или токен сервера не заданы в настройках" }
        val json = JSONObject().put("id", serverId).put("decision", decision).put("grams", grams ?: JSONObject.NULL).put("aaps_note", aapsNote ?: JSONObject.NULL)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        return call(Request.Builder().url("${base()}/v1/decision").header("X-Food-Token", token()).post(body).build())
    }

    private fun call(request: Request): JSONObject {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw Failure("нет связи с сервером: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty().ifEmpty { text.take(120) }
                throw when (r.code) {
                    401       -> Failure("сервер не принял токен — проверьте настройки", retryable = false)
                    400, 404  -> Failure("сервер отказал: $detail", retryable = false)
                    else      -> Failure("сервер ответил ${r.code}: $detail", retryable = true)
                }
            }
            return runCatching { JSONObject(text) }.getOrElse { throw Failure("непонятный ответ сервера", retryable = true) }
        }
    }
}
