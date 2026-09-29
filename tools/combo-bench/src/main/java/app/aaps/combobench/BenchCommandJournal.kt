package app.aaps.combobench

internal data class BenchCommandRequest(val id: String, val generation: Long, val fingerprint: String)
internal enum class BenchCommandOutcome { SUBMITTED, UNKNOWN, ACKNOWLEDGED }
internal data class BenchCommandRecord(val request: BenchCommandRequest, val outcome: BenchCommandOutcome)
internal data class BenchCommandSnapshot(
    val session: String,
    val pump: String,
    val node: String,
    val records: Map<String, BenchCommandRecord> = emptyMap()
)

internal interface BenchCommandStore {
    fun load(): BenchCommandSnapshot
    // Must durably replace the snapshot or throw. Missing storage must not mean an empty journal.
    fun save(snapshot: BenchCommandSnapshot)
}

/** Offline/stend prototype. Not connected to a pump, AAPS, or any therapeutic command queue. */
internal class BenchCommandJournal(
    private val identity: BenchIdentity,
    private val ownership: DiagnosticOwnership,
    private val store: BenchCommandStore
) {
    private var storageFault = false

    @Synchronized
    fun snapshot(): BenchCommandSnapshot {
        check(!storageFault) { "Command journal has a storage fault" }
        return try {
            store.load().also { data ->
                require(data.session == identity.session && data.pump == identity.pump && data.node == identity.local)
                data.records.forEach { (id, record) ->
                    validate(record.request)
                    require(id == record.request.id)
                }
            }
        } catch (e: Exception) {
            storageFault = true
            throw e
        }
    }

    @Synchronized
    fun executeSimulated(request: BenchCommandRequest, acknowledgedSimulation: () -> Unit) {
        validate(request)
        val state = ownership.state()
        check(state.owner == identity.local && state.generation == request.generation) { "Request belongs to another owner or generation" }
        val before = snapshot()
        check(request.id !in before.records) { "This request was already submitted; it will not be repeated" }
        check(before.records.values.all { it.outcome == BenchCommandOutcome.ACKNOWLEDGED }) { "A previous command has an unresolved outcome" }

        // If a crash separates the two writes, the ownership operation still blocks handoff/retry.
        ownership.beginOperation(operationId(request))
        save(before.copy(records = before.records + (request.id to BenchCommandRecord(request, BenchCommandOutcome.SUBMITTED))))
        try {
            // Only tests call this callback. A return models a receipt, not actual Combo evidence.
            acknowledgedSimulation()
        } catch (e: Exception) {
            try { update(request, BenchCommandOutcome.UNKNOWN) }
            catch (storageError: Exception) { e.addSuppressed(storageError) }
            throw e
        }
        update(request, BenchCommandOutcome.ACKNOWLEDGED)
        ownership.completeOperation(operationId(request))
    }

    /** Recover a crash after persisting acknowledgement but before releasing the local operation. */
    @Synchronized
    fun finishAcknowledged(request: BenchCommandRequest) {
        validate(request)
        val state = ownership.state()
        check(state.owner == identity.local && state.generation == request.generation)
        val record = snapshot().records[request.id]
        check(record == BenchCommandRecord(request, BenchCommandOutcome.ACKNOWLEDGED)) { "No matching durable acknowledgement" }
        ownership.completeOperation(operationId(request))
    }

    private fun update(request: BenchCommandRequest, outcome: BenchCommandOutcome) {
        val data = snapshot()
        check(data.records[request.id]?.request == request)
        save(data.copy(records = data.records + (request.id to BenchCommandRecord(request, outcome))))
    }

    private fun save(snapshot: BenchCommandSnapshot) {
        try { store.save(snapshot) }
        catch (e: Exception) { storageFault = true; throw e }
    }

    private fun operationId(request: BenchCommandRequest) = "simulated-command:${request.id}"

    private fun validate(request: BenchCommandRequest) {
        require(request.id.isNotBlank() && request.id.length <= 128 && request.generation >= 0)
        require(request.fingerprint.matches(Regex("[a-f0-9]{64}")))
    }
}
