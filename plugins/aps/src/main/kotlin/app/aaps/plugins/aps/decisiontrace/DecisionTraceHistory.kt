package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionTraceStep

internal enum class TraceRunState { CALCULATING, CALCULATED, FAILED }

internal data class TraceRun(val id: Long, val steps: List<DecisionTraceStep>, val state: TraceRunState, val context: DecisionContext? = null)

/** Read-only observation buffer. No observers or UI callbacks execute on the calculation thread. */
internal class DecisionTraceHistory(private val capacity: Int = 12) {
    private data class Entry(val steps: MutableList<DecisionTraceStep>, var state: TraceRunState, var context: DecisionContext? = null)
    private val runs = linkedMapOf<Long, Entry>()
    var revision = 0L
        @Synchronized get
        private set

    @Synchronized fun begin(id: Long) {
        runs[id] = Entry(mutableListOf(), TraceRunState.CALCULATING)
        trim()
        revision++
    }

    @Synchronized fun append(id: Long, step: DecisionTraceStep) {
        val run = runs[id] ?: return
        if (run.state != TraceRunState.CALCULATING || step.sequence != run.steps.size + 1) return
        run.steps.add(step)
        revision++
    }

    @Synchronized fun finish(id: Long, failed: Boolean = false) {
        runs[id]?.state = if (failed) TraceRunState.FAILED else TraceRunState.CALCULATED
        revision++
    }

    @Synchronized fun merge(id: Long, steps: List<DecisionTraceStep>, context: DecisionContext? = null) {
        if (steps.isEmpty() || steps.withIndex().any { it.value.sequence != it.index + 1 }) return
        val previous = runs[id]
        // A late pump response belongs to its original calculation, never the newest one.
        if (previous != null) {
            if (steps.size < previous.steps.size || steps.take(previous.steps.size) != previous.steps) return
            if (steps.size == previous.steps.size && (context == null || previous.context == context)) return
            previous.steps.addAll(steps.drop(previous.steps.size))
            if (context != null) previous.context = context
        } else {
            runs[id] = Entry(steps.toMutableList(), TraceRunState.CALCULATED, context)
            trim()
        }
        revision++
    }

    @Synchronized fun snapshots(): List<TraceRun> = runs.entries.sortedByDescending { it.key }
        .map { TraceRun(it.key, it.value.steps.toList(), it.value.state, it.value.context) }

    private fun trim() {
        while (runs.size > capacity.coerceAtLeast(1)) runs.remove(runs.keys.minOrNull())
    }
}

internal object DecisionTraceLive {
    val history = DecisionTraceHistory()
}
