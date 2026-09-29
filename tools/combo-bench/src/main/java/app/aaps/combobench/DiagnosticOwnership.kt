package app.aaps.combobench

data class BenchIdentity(val session: String, val local: String, val peer: String, val pump: String) {
    init {
        require(listOf(session, local, peer, pump).all { it.isNotBlank() })
        require(local != peer)
    }
}

data class DiagnosticGrant(
    val id: String,
    val session: String,
    val pump: String,
    val from: String,
    val to: String,
    val previousGeneration: Long,
    val generation: Long
)

data class OwnershipState(
    val owner: String,
    val generation: Long = 0,
    val outbox: DiagnosticGrant? = null,
    val acceptedGrant: DiagnosticGrant? = null,
    val operation: String? = null
)

interface OwnershipStore {
    fun load(): OwnershipState
    // Must durably replace the entire record or throw. No asynchronous apply().
    fun save(state: OwnershipState)
}

/** Bench-only ownership; it does not fence AAPS or a pump's other controllers. */
class DiagnosticOwnership(private val identity: BenchIdentity, private val store: OwnershipStore) {
    private var storageFault = false

    @Synchronized
    fun state(): OwnershipState {
        check(!storageFault) { "Storage fault; diagnostic controller is locked" }
        return try {
            store.load().also { state ->
                require(state.owner == identity.local || state.owner == identity.peer)
                require(state.generation >= 0)
                state.outbox?.let { grant ->
                    require(grant.session == identity.session && grant.pump == identity.pump)
                    require(grant.from == identity.local && grant.to == identity.peer)
                    require(grant.generation == state.generation && state.owner == identity.peer)
                }
            }
        } catch (e: Exception) {
            storageFault = true
            throw e
        }
    }

    @Synchronized
    fun transfer(id: String): DiagnosticGrant {
        require(id.isNotBlank())
        val state = state()
        check(state.owner == identity.local) { "This device is not the diagnostic owner" }
        check(state.operation == null) { "An operation is running or its result is unknown" }
        val grant = DiagnosticGrant(id, identity.session, identity.pump, identity.local,
            identity.peer, state.generation, Math.addExact(state.generation, 1))
        persist(state.copy(owner = identity.peer, generation = grant.generation, outbox = grant))
        return grant
    }

    @Synchronized
    fun accept(source: String, grant: DiagnosticGrant) {
        require(source == identity.peer && grant.from == source && grant.to == identity.local)
        require(grant.session == identity.session && grant.pump == identity.pump && grant.id.isNotBlank())
        require(grant.previousGeneration >= 0 && grant.generation == Math.addExact(grant.previousGeneration, 1))
        val state = state()
        if (state.acceptedGrant == grant && state.owner == identity.local && state.generation == grant.generation) return
        check(state.owner == source && state.generation == grant.previousGeneration) { "Stale or conflicting handoff" }
        check(state.operation == null)
        persist(state.copy(owner = identity.local, generation = grant.generation, outbox = null, acceptedGrant = grant))
    }

    @Synchronized
    fun acknowledge(source: String, grant: DiagnosticGrant) {
        require(source == identity.peer)
        val state = state()
        if (state.outbox == grant) persist(state.copy(outbox = null))
    }

    @Synchronized
    fun beginOperation(id: String) {
        require(id.isNotBlank())
        val state = state()
        check(state.owner == identity.local) { "Diagnostic permission belongs to the other device" }
        check(state.operation == null) { "Previous operation has no recorded completion" }
        persist(state.copy(operation = id))
    }

    @Synchronized
    fun completeOperation(id: String) {
        val state = state()
        check(state.operation == id)
        persist(state.copy(operation = null))
    }

    private fun persist(state: OwnershipState) {
        try { store.save(state) } catch (e: Exception) {
            storageFault = true
            throw e
        }
    }
}
