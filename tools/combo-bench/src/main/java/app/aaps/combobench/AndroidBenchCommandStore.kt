package app.aaps.combobench

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Explicitly provisioned local prototype journal. Contains no pairing keys or treatment amounts. */
internal class AndroidBenchCommandStore(context: Context, private val identity: BenchIdentity) : BenchCommandStore {
    private val files = BenchFiles(context)
    private val name = "bench-command-journal-v1.json"

    @Synchronized
    fun provision() {
        check(!files.exists(name)) { "Existing command history will not be overwritten" }
        save(BenchCommandSnapshot(identity.session, identity.pump, identity.local))
    }

    @Synchronized
    override fun load(): BenchCommandSnapshot {
        val data = files.read(name)
        require(data.getInt("version") == 1)
        val rows = data.getJSONArray("records")
        val records = mutableMapOf<String, BenchCommandRecord>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val request = BenchCommandRequest(row.getString("id"), row.getLong("generation"), row.getString("fingerprint"))
            require(request.id !in records) { "Duplicate record in command journal" }
            records[request.id] = BenchCommandRecord(request, BenchCommandOutcome.valueOf(row.getString("outcome")))
        }
        return BenchCommandSnapshot(data.getString("session"), data.getString("pump"), data.getString("node"), records).also(::verify)
    }

    @Synchronized
    override fun save(snapshot: BenchCommandSnapshot) {
        verify(snapshot)
        val rows = JSONArray()
        snapshot.records.forEach { (id, record) ->
            require(id == record.request.id)
            rows.put(JSONObject().put("id", id).put("generation", record.request.generation)
                .put("fingerprint", record.request.fingerprint).put("outcome", record.outcome.name))
        }
        files.write(name, JSONObject().put("version", 1).put("session", snapshot.session)
            .put("pump", snapshot.pump).put("node", snapshot.node).put("records", rows))
    }

    private fun verify(snapshot: BenchCommandSnapshot) {
        require(snapshot.session == identity.session && snapshot.pump == identity.pump && snapshot.node == identity.local)
    }
}
