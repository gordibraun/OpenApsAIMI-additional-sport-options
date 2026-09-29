package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IsfStateStore @Inject constructor(private val sp: SP, private val logger: AAPSLogger) {
    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <reified T> read(key: String): T? = try {
        sp.getStringOrNull(key, null)?.let { json.decodeFromString<T>(it) }
    } catch (e: Exception) {
        logger.warn(LTag.APS, "Cannot restore ISF state $key: ${e.javaClass.simpleName}")
        null
    }

    private inline fun <reified T> write(key: String, state: T) {
        try {
            sp.putString(key, json.encodeToString(state))
        } catch (e: Exception) {
            logger.warn(LTag.APS, "Cannot persist ISF state $key: ${e.javaClass.simpleName}")
        }
    }

    fun adaptive(context: String, now: Long): AdaptiveIsfState? =
        read<AdaptiveIsfState>("aimi_adaptive_isf_v1")?.takeIf { it.isUsable(context, now) }

    fun save(state: AdaptiveIsfState) = write("aimi_adaptive_isf_v1", state)
    fun history(): StoredIsfHistory? = read("aimi_food_isf_history_v1")
    fun save(state: StoredIsfHistory) = write("aimi_food_isf_history_v1", state)
    fun fusion(owner: String, context: String, now: Long): FusionIsfState? =
        read<FusionIsfState>("aimi_isf_fusion_${owner}_v1")?.takeIf { it.isUsable(context, now) }

    fun saveFusion(owner: String, state: FusionIsfState) = write("aimi_isf_fusion_${owner}_v1", state)
}
