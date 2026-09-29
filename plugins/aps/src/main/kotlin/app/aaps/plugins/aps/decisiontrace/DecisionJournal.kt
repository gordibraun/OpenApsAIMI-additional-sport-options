package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.DecisionStepKind
import app.aaps.core.interfaces.aps.DecisionValue
import app.aaps.core.interfaces.aps.DecisionBranch

/** Records execution order, including interleaved legacy diagnostics, without parsing their text. */
internal class DecisionJournal(private val onStep: (DecisionTraceStep) -> Unit = {}) {
    private val entries = mutableListOf<DecisionTraceStep>()
    private var stage = DecisionStage.INPUT
    private var startedNanos = System.nanoTime()

    fun reset() {
        entries.clear()
        stage = DecisionStage.INPUT
        startedNanos = System.nanoTime()
    }

    fun enter(stage: DecisionStage, title: String, detail: String = "", values: List<DecisionValue> = emptyList()) {
        this.stage = stage
        append(title, detail, DecisionStepKind.CHECKPOINT, values)
    }

    fun record(title: String, detail: String) {
        append(title, detail, DecisionStepKind.DETAIL)
    }

    fun change(title: String, detail: String, vararg values: DecisionValue) {
        append(title, detail, DecisionStepKind.CHANGE, values.toList())
    }

    fun branch(id: String, outcome: String, title: String, detail: String = "", values: List<DecisionValue> = emptyList()) {
        append(title, detail, DecisionStepKind.CHECKPOINT, values, DecisionBranch(id, outcome))
    }

    private fun append(title: String, detail: String, kind: DecisionStepKind, values: List<DecisionValue> = emptyList(), branch: DecisionBranch? = null) {
        val step = DecisionTraceStep(entries.size + 1, stage, title, detail, kind, values,
            ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0), branch)
        entries.add(step)
        onStep(step)
    }

    fun snapshot(): List<DecisionTraceStep> = entries.toList()

    fun log(source: String): MutableList<String> = object : AbstractMutableList<String>() {
        private val values = mutableListOf<String>()
        override val size: Int get() = values.size
        override fun get(index: Int): String = values[index]
        override fun add(index: Int, element: String) {
            values.add(index, element)
            record(source, element)
        }
        override fun set(index: Int, element: String): String = values.set(index, element).also { record(source, element) }
        override fun removeAt(index: Int): String = values.removeAt(index)
    }
}
