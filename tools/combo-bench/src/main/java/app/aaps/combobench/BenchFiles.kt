package app.aaps.combobench

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Durable JSON records; the bench never treats a missing or corrupt record as empty history. */
internal interface JsonFiles {
    fun exists(name: String): Boolean
    fun read(name: String): JSONObject
    fun write(name: String, value: JSONObject)
}

internal class BenchFiles(context: Context) : JsonFiles {
    private val directory = context.filesDir

    override fun exists(name: String) = AtomicFile(File(directory, name)).let { it.baseFile.exists() || File(directory, "$name.bak").exists() }

    override fun read(name: String): JSONObject = AtomicFile(File(directory, name)).openRead().use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8))
    }

    override fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
            check(read(name).toString() == value.toString()) { "State verification failed" }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun consumeArm(session: String, pump: String): ProbeOptions {
        val arm = read("arm.json")
        require(arm.getString("session") == session && arm.getString("pump") == pump)
        val remaining = arm.getLong("expiresAt") - System.currentTimeMillis()
        require(remaining in 1..600_000) { "Bench permission expired" }
        check(arm.getBoolean("offBody") && arm.getBoolean("aapsStopped") && arm.getBoolean("aapsDisabled"))
        val transport = ProbeTransport.parse(arm.optString("transport", "sdp"))
        val timeout = if (arm.has("timeoutSeconds")) arm.get("timeoutSeconds") else 8
        require(timeout is Int) { "Diagnostic deadline must be an integer" }
        val hold = if (arm.has("holdSeconds")) arm.get("holdSeconds") else 0
        require(hold is Int) { "Socket hold duration must be an integer" }
        val options = ProbeOptions(transport, timeout, hold)
        require(remaining > options.permissionBudgetMs) { "Insufficient diagnostic permission time" }
        AtomicFile(File(directory, "arm.json")).delete()
        check(!exists("arm.json"))
        return options
    }

    fun consumePairingArm(session: String, pump: String) {
        val arm = read("pairing-arm.json")
        require(arm.getString("session") == session && arm.getString("pump") == pump)
        require(arm.getLong("expiresAt") - System.currentTimeMillis() in 1..120_000)
        check(arm.getBoolean("offBody") && arm.getBoolean("aapsDisabled") && arm.getBoolean("allowReplacePairing"))
        AtomicFile(File(directory, "pairing-arm.json")).delete()
        check(!exists("pairing-arm.json"))
    }

    fun consumeControlSessionArm(session: String, pump: String): String {
        val arm = read("control-session-arm.json")
        check(BuildConfig.FLAVOR == "watch" && pump == "PUMP_41056642")
        require(arm.getString("session") == session && arm.getString("pump") == pump)
        require(arm.getString("mode") == "control-handshake-only")
        val id = java.util.UUID.fromString(arm.getString("id")).toString()
        require(arm.getLong("expiresAt") - System.currentTimeMillis() in 40_000..120_000)
        check(arm.getBoolean("offBody") && arm.getBoolean("aapsStopped") && arm.getBoolean("aapsDisabled"))
        check(arm.getBoolean("ownWatchPairingOnly") && arm.getBoolean("noTherapyOrServices"))
        AtomicFile(File(directory, "control-session-arm.json")).delete()
        check(!exists("control-session-arm.json"))
        return id
    }

    fun consumePeerArm(session: String, mode: String): JSONObject {
        val arm = read("peer-control-arm.json")
        require(arm.getString("mode") == mode)
        java.util.UUID.fromString(arm.getString("id"))
        PeerControlPolicy.validate(BuildConfig.FLAVOR, mode, arm.getString("peer"), arm.getString("session"),
            session, arm.getLong("expiresAt") - System.currentTimeMillis(), arm.getBoolean("noPumpAccess"))
        AtomicFile(File(directory, "peer-control-arm.json")).delete()
        check(!exists("peer-control-arm.json"))
        return arm
    }
}

internal fun DiagnosticGrant.toJson() = JSONObject().put("id", id).put("session", session)
    .put("pump", pump).put("from", from).put("to", to)
    .put("previousGeneration", previousGeneration).put("generation", generation)

internal fun JSONObject.toGrant() = DiagnosticGrant(getString("id"), getString("session"),
    getString("pump"), getString("from"), getString("to"), getLong("previousGeneration"), getLong("generation"))

internal fun OwnershipState.toJson() = JSONObject().put("owner", owner).put("generation", generation)
    .put("outbox", outbox?.toJson()).put("acceptedGrant", acceptedGrant?.toJson()).put("operation", operation)

internal fun JSONObject.toOwnershipState() = OwnershipState(getString("owner"), getLong("generation"),
    optJSONObject("outbox")?.toGrant(), optJSONObject("acceptedGrant")?.toGrant(),
    if (has("operation")) getString("operation") else null)
