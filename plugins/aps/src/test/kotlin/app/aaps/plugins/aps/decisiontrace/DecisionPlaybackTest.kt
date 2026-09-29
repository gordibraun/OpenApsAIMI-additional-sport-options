package app.aaps.plugins.aps.decisiontrace

import app.aaps.core.interfaces.aps.DecisionStage
import app.aaps.core.interfaces.aps.DecisionBranch
import app.aaps.core.interfaces.aps.DecisionStepKind
import app.aaps.core.interfaces.aps.DecisionTraceStep
import app.aaps.core.interfaces.aps.DecisionValue
import app.aaps.core.interfaces.aps.RT
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DecisionPlaybackTest {
    private fun event(number: Int, stage: DecisionStage = DecisionStage.INPUT, kind: DecisionStepKind = DecisionStepKind.CHECKPOINT) =
        DecisionTraceStep(number, stage, "event $number", "recorded", kind)

    @Test fun liveAndPersistedEventsAreTheSameAndMonotonic() {
        val live = mutableListOf<DecisionTraceStep>()
        val journal = DecisionJournal(live::add)
        journal.enter(DecisionStage.SENSITIVITY, "input")
        journal.change("adjust", "reason", DecisionValue("ISF", "55", "50", "mg/dL/U"))
        journal.record("diagnostic", "detail")
        assertEquals(journal.snapshot(), live)
        assertTrue(live.zipWithNext().all { (a, b) -> a.elapsedMs!! <= b.elapsedMs!! })
        assertEquals(live, RT.deserialize(RT(runningDynamicIsf = true, decisionTrace = live).serialize()).decisionTrace)
    }

    @Test fun oldRecordsWithoutNewFieldsRemainReadable() {
        val raw = """{"runningDynamicIsf":true,"decisionTrace":[{"sequence":1,"stage":"INPUT","title":"old","detail":"data"}]}"""
        val step = RT.deserialize(raw).decisionTrace.single()
        assertEquals(DecisionStepKind.DETAIL, step.kind)
        assertTrue(step.values.isEmpty())
        assertNull(step.elapsedMs)
    }

    @Test fun historyKeepsLatePumpReceiptOnOriginalRun() {
        val history = DecisionTraceHistory()
        history.begin(100)
        history.append(100, event(1, DecisionStage.FINAL))
        history.finish(100)
        history.begin(200)
        history.append(200, event(1))
        history.merge(100, listOf(event(1, DecisionStage.FINAL), event(2, DecisionStage.DELIVERY)))
        assertEquals(200, history.snapshots()[0].id)
        assertEquals(1, history.snapshots()[0].steps.size)
        assertEquals(DecisionStage.DELIVERY, history.snapshots()[1].steps.last().stage)
    }

    @Test fun snapshotsAreNotMutatedByNewEventsAndNoGapsAreAccepted() {
        val history = DecisionTraceHistory()
        history.begin(100)
        history.append(100, event(1))
        val snapshot = history.snapshots().single()
        history.append(100, event(3))
        assertEquals(1, history.snapshots().single().steps.size)
        history.append(100, event(2))
        assertEquals(1, snapshot.steps.size)
        assertEquals(2, history.snapshots().single().steps.size)
    }

    @Test fun historyRejectsConflictingOrShorterCompletedRecords() {
        val history = DecisionTraceHistory()
        val original = listOf(event(1), event(2, DecisionStage.FINAL))
        history.merge(100, original)
        history.merge(100, listOf(event(1)))
        history.merge(100, listOf(event(1, DecisionStage.SAFETY), event(2), event(3)))
        assertEquals(original, history.snapshots().single().steps)
    }

    @Test fun historyIsBoundedAndDoesNotChangeOrderForLateUpdates() {
        val history = DecisionTraceHistory(2)
        (1L..3L).forEach { history.merge(it, listOf(event(1))) }
        history.merge(1, listOf(event(1), event(2)))
        assertEquals(listOf(3L, 2L), history.snapshots().map { it.id })
    }

    @Test fun failedRunIsNotShownAsCalculated() {
        val history = DecisionTraceHistory()
        history.begin(1)
        history.finish(1, failed = true)
        assertEquals(TraceRunState.FAILED, history.snapshots().single().state)
    }

    @Test fun playbackFreezesAtChosenSnapshotWhileNewRunArrives() {
        val history = DecisionTraceHistory()
        history.merge(100, listOf(event(1), event(2)))
        val playback = DecisionPlayback()
        playback.replay(history.snapshots().single())
        history.merge(100, listOf(event(1), event(2), event(3)))
        history.merge(200, listOf(event(1)))
        assertEquals(100, playback.run!!.id)
        assertEquals(2, playback.steps.size)
        assertFalse(playback.following)
    }

    @Test fun detailFilterPreservesActualSequenceAndRepeatedStageVisits() {
        val steps = listOf(event(1), event(2, kind = DecisionStepKind.DETAIL),
            event(3, DecisionStage.FORECAST), event(4, DecisionStage.SAFETY), event(5, DecisionStage.FORECAST))
        val playback = DecisionPlayback()
        playback.replay(TraceRun(100, steps, TraceRunState.CALCULATED))
        assertEquals(listOf(1, 3, 4, 5), playback.steps.map { it.sequence })
        playback.seek(1)
        playback.showAll(true)
        assertEquals(3, playback.selected!!.sequence)
        assertEquals(5, playback.steps.size)
    }

    @Test fun endAndEmptyPlaybackNeverInventAdditionalSteps() {
        val playback = DecisionPlayback()
        assertFalse(playback.next())
        playback.seek(900)
        assertEquals(0, playback.index)
        playback.replay(TraceRun(100, listOf(event(1)), TraceRunState.CALCULATED))
        assertFalse(playback.next())
        playback.previous()
        assertEquals(1, playback.selected!!.sequence)
    }

    @Test fun liveFollowsLastEventAndScrubbingOnlyChangesCursor() {
        val playback = DecisionPlayback()
        val run = TraceRun(100, listOf(event(1), event(2)), TraceRunState.CALCULATED)
        playback.follow(run)
        assertEquals(2, playback.selected!!.sequence)
        playback.seek(0)
        assertFalse(playback.following)
        assertEquals(2, run.steps.size)
        assertEquals(1, playback.selected!!.sequence)
    }

    @Test fun inspectingRunShowsRecordedPathBeforeExplicitReplayStarts() {
        val run = TraceRun(100, (1..6).map { event(it) } +
            event(7).copy(branch = DecisionBranch("meal_mode", "normal")) +
            event(8, DecisionStage.FINAL).copy(branch = DecisionBranch("final", "basal")), TraceRunState.CALCULATED)
        val playback = DecisionPlayback()
        playback.inspect(run)
        assertFalse(playback.following)
        assertEquals(8, playback.selected!!.sequence)
        assertEquals(2, DecisionBranchMap.visits(run.steps, playback.selected!!.sequence).count { it.selected != null })

        playback.replay(run)
        assertEquals(1, playback.selected!!.sequence)
        assertTrue(DecisionBranchMap.visits(run.steps, playback.selected!!.sequence).all { it.selected == null })
    }

    @Test fun inspectingEmptyOrFilteredRunUsesLastVisibleStepWithoutInventingEvents() {
        val playback = DecisionPlayback()
        playback.inspect(TraceRun(100, emptyList(), TraceRunState.CALCULATING))
        assertNull(playback.selected)
        val run = TraceRun(200, listOf(event(1), event(2), event(3, kind = DecisionStepKind.DETAIL)), TraceRunState.CALCULATED)
        playback.inspect(run)
        assertEquals(2, playback.selected!!.sequence)
        assertEquals(3, run.steps.size)
    }
}
