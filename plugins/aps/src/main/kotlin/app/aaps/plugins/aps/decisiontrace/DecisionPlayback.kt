package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionStepKind
import app.aaps.core.interfaces.aps.DecisionTraceStep

/** A cursor over immutable recorded events; it cannot invoke APS or enqueue a pump command. */
internal class DecisionPlayback {
    var run: TraceRun? = null
        private set
    var index = 0
        private set
    var following = true
        private set
    var allEntries = false
        private set
    val steps: List<DecisionTraceStep>
        get() = run?.steps.orEmpty().filterIndexed { i, step ->
            allEntries || step.kind != DecisionStepKind.DETAIL || i == 0 || run?.steps?.get(i - 1)?.stage != step.stage
        }
    val selected: DecisionTraceStep? get() = steps.getOrNull(index)

    fun follow(run: TraceRun) {
        following = true
        this.run = run.copy(steps = run.steps.toList())
        index = (steps.size - 1).coerceAtLeast(0)
    }

    fun replay(run: TraceRun) {
        following = false
        this.run = run.copy(steps = run.steps.toList())
        index = 0
    }

    fun inspect(run: TraceRun) {
        replay(run)
        index = (steps.size - 1).coerceAtLeast(0)
    }

    fun showAll(all: Boolean) {
        val sequence = selected?.sequence
        allEntries = all
        index = if (following) (steps.size - 1).coerceAtLeast(0)
        else steps.indexOfLast { it.sequence <= (sequence ?: 1) }.coerceAtLeast(0)
    }

    fun seek(index: Int) {
        following = false
        this.index = index.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    }

    fun next(): Boolean {
        if (index >= steps.lastIndex) return false
        seek(index + 1)
        return true
    }

    fun previous() = seek(index - 1)
}
