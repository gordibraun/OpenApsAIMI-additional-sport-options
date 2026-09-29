package app.aaps.plugins.sync.wear.wearintegration

import java.util.UUID

/** A confirmation is bound to one watch, used once, and lost on process restart. */
internal class PendingWatchActions<T>(private val lifetimeMs: Long = 60_000L) {
    private data class Entry<T>(val node: String, val issued: Long, val value: T)
    private val pending = mutableMapOf<String, Entry<T>>()
    private val seen = mutableMapOf<Pair<String, String>, Long>()

    @Synchronized fun issue(node: String, requestId: String, now: Long, value: T): String? {
        if (node.isBlank() || requestId.isBlank()) return null
        pending.entries.removeAll { now - it.value.issued !in 0..lifetimeMs }
        seen.entries.removeAll { now - it.value !in 0..600_000L }
        if (node to requestId in seen || seen.size >= 100) return null
        seen[node to requestId] = now
        pending.entries.removeAll { it.value.node == node }
        val token = UUID.randomUUID().toString()
        pending[token] = Entry(node, now, value)
        return token
    }

    @Synchronized fun consume(node: String, token: String, now: Long): T? {
        val entry = pending[token] ?: return null
        if (entry.node != node) return null
        pending.remove(token)
        return entry.value.takeIf { now - entry.issued in 0..lifetimeMs }
    }
}
