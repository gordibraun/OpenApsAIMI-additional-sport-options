package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.aps.recordDecisionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

class DecisionJournalTest {
    @Test fun preservesOrderAndDuplicateDiagnosticsWithoutKeywordInference() {
        val journal = DecisionJournal()
        val normal = journal.log("normal")
        val diagnostic = journal.log("diagnostic")
        journal.enter(DecisionStage.CARBS, "carbs")
        normal.add("SMB mentioned while calculating food")
        diagnostic.add("same")
        normal.addAll(listOf("same", "last"))
        val steps = journal.snapshot()
        assertEquals((1..5).toList(), steps.map { it.sequence })
        assertEquals(listOf("", "SMB mentioned while calculating food", "same", "same", "last"), steps.map { it.detail })
        assertTrue(steps.all { it.stage == DecisionStage.CARBS })
    }

    @Test fun completedResultIsUnaffectedByNextRun() {
        val journal = DecisionJournal()
        journal.enter(DecisionStage.SAFETY, "early return")
        val result = RT(runningDynamicIsf = true, decisionTrace = journal.snapshot())
        journal.reset()
        journal.enter(DecisionStage.INPUT, "new run")
        assertEquals("early return", result.decisionTrace.single().title)
    }

    @Test fun traceSurvivesSerializationAndConstraintsAreAppendedAfterCalculation() {
        val journal = DecisionJournal()
        journal.enter(DecisionStage.FINAL, "proposed", "0.5 U")
        val request = RT(runningDynamicIsf = true, decisionTrace = journal.snapshot())
        val constrained = RT.deserialize(request.serialize())
        constrained.recordDecisionStep(DecisionStage.CONSTRAINTS, "limit", "0.5 -> 0.0 U")
        assertEquals(1, request.decisionTrace.size)
        assertEquals(2, constrained.decisionTrace.last().sequence)
        assertEquals(constrained.decisionTrace, RT.deserialize(constrained.serialize()).decisionTrace)
        assertTrue(RT.deserialize("{\"runningDynamicIsf\":true}").decisionTrace.isEmpty())
    }
}
