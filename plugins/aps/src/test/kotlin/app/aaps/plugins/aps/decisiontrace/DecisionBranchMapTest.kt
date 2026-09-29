package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionBranch
import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.RT
import org.junit.Assert.*
import org.junit.jupiter.api.Test

class DecisionBranchMapTest {
    private fun step(sequence: Int, id: String, outcome: String) = DecisionTraceStep(
        sequence, DecisionStage.SAFETY, "Recorded", "Actual condition", branch = DecisionBranch(id, outcome))

    @Test fun catalogueIdsAndOptionsAreUnique() {
        assertEquals(DecisionBranchMap.nodes.size, DecisionBranchMap.nodes.distinctBy { it.id }.size)
        DecisionBranchMap.nodes.forEach { assertEquals(it.options.size, it.options.distinctBy { option -> option.id }.size) }
    }

    @Test fun softCapIsNotDisplayedAsHardStop() {
        val visits = DecisionBranchMap.visits(listOf(step(1, "early", "cap"), step(2, "early_return", "continue")))
        assertEquals("cap", visits[0].selected?.id)
        assertEquals("continue", visits[1].selected?.id)
        assertNull(visits.first { it.node.id == "critical.hypo" }.selected)
    }

    @Test fun replayDoesNotRevealFutureChoices() {
        val visits = DecisionBranchMap.visits(listOf(step(1, "early", "cap"), step(9, "final", "insulin")), 5)
        assertEquals("cap", visits.first().selected?.id)
        assertNull(visits.first { it.node.id == "final" }.step)
    }

    @Test fun repeatedEvaluationsKeepTheirOrderAndOutcomes() {
        val visits = DecisionBranchMap.visits(listOf(step(8, "early", "pass"), step(2, "early", "cap")))
        assertEquals(listOf(2, 8), visits.mapNotNull { it.step?.sequence })
        assertEquals(listOf("cap", "pass"), visits.filter { it.step != null }.map { it.selected?.id })
    }

    @Test fun unknownOutcomeDoesNotBecomePassOrBlock() {
        val visit = DecisionBranchMap.visits(listOf(step(1, "early", "new_outcome"))).first()
        assertNotNull(visit.step)
        assertNull(visit.selected)
    }

    @Test fun legacyTextDoesNotInventBranches() {
        val visits = DecisionBranchMap.visits(listOf(DecisionTraceStep(1, DecisionStage.SMB,
            "SMB delivered", "early guard passed, final insulin")))
        assertTrue(visits.all { it.step == null && it.selected == null })
    }

    @Test fun unknownNodeIsNotSilentlyDropped() {
        val visit = DecisionBranchMap.visits(listOf(step(1, "future_node", "actual"))).first()
        assertEquals("future_node", visit.node.id)
        assertEquals("actual", visit.selected?.id)
    }

    @Test fun requestIsNotPumpConfirmation() {
        val visits = DecisionBranchMap.visits(listOf(step(1, "delivery_smb", "requested")))
        assertNull(visits.first { it.node.id == "delivery_result" }.selected)
    }

    @Test fun structuredChoicesSurvivePersistenceAndOldDataStillLoads() {
        val rt = RT(runningDynamicIsf = true, decisionTrace = listOf(step(1, "early", "cap")))
        assertEquals(rt.decisionTrace, RT.deserialize(rt.serialize()).decisionTrace)
        val old = RT.deserialize("""{"runningDynamicIsf":true,"decisionTrace":[{"sequence":1,"stage":"SMB","title":"old","detail":"text"}]}""")
        assertNull(old.decisionTrace.single().branch)
    }

    @Test fun branchEventsArePublishedInExecutionOrder() {
        val published = mutableListOf<DecisionTraceStep>()
        val journal = DecisionJournal(published::add)
        journal.branch("early", "cap", "Soft limit")
        journal.branch("early_return", "continue", "Continue")
        assertEquals(published, journal.snapshot())
        assertEquals(listOf("cap", "continue"), published.map { it.branch?.outcome })
    }
}
