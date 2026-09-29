package app.aaps.core.objects.aps

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.TreatmentInputStamp
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BolusInputSnapshotTest {
    private val now = 10_000_000L
    private val since = now - 5 * 3_600_000L
    private fun bolus(time: Long = now - 600_000, amount: Double = 1.0, created: Long = time) =
        BS(timestamp = time, amount = amount, dateCreated = created, type = BS.Type.NORMAL)
    private fun stamp(b: BS) = TreatmentInputStamp(b.timestamp, b.amount, b.dateCreated)
    private fun input(vararg boluses: BS) = IobTotal(now, bolusInputs = boluses.map(::stamp), bolusInputsSince = since)

    @Test fun unchangedHistoryAndEmptyHistoryPass() {
        val b = bolus()
        assertThat(BolusInputSnapshot.from(input(b))!!.matches(listOf(b), now + 30_000)).isTrue()
        assertThat(BolusInputSnapshot.from(input())!!.matches(emptyList(), now + 30_000)).isTrue()
    }

    @Test fun september15LateImportedTwoUnitsInvalidateOldSmbEvenAfterFrequencyInterval() {
        val known = bolus(now - 74 * 60_000)
        val first = bolus(now - 8 * 60_000, created = now + 20_000)
        val second = bolus(now - 3 * 60_000, created = now + 20_000)
        val snapshot = BolusInputSnapshot.from(input(known))!!
        assertThat(snapshot.matches(listOf(known, first, second), now + 39_000)).isFalse()
        assertThat(snapshot.lastBolusTime).isEqualTo(known.timestamp)
    }

    @Test fun lateOlderBolusIsDetectedEvenWhenNewestTimestampDoesNotChange() {
        val known = bolus(now - 60_000)
        assertThat(BolusInputSnapshot.from(input(known))!!.matches(listOf(known, bolus(created = now + 1)), now + 2)).isFalse()
    }

    @Test fun editsInvalidationsAndDuplicateDosesAreDetected() {
        val b = bolus()
        val snapshot = BolusInputSnapshot.from(input(b))!!
        assertThat(snapshot.matches(listOf(b.copy(amount = 2.0)), now + 1)).isFalse()
        assertThat(snapshot.matches(listOf(b.copy(isValid = false)), now + 1)).isFalse()
        assertThat(snapshot.matches(listOf(b, b.copy()), now + 1)).isFalse()
        assertThat(snapshot.matches(emptyList(), now + 1)).isFalse()
    }

    @Test fun orderingAndUnrelatedOldOrFutureEntriesDoNotBlock() {
        val a = bolus()
        val b = bolus(now - 120_000)
        assertThat(BolusInputSnapshot.from(input(a,b))!!.matches(listOf(b,a,bolus(since-1),bolus(now+60_000)), now+1)).isTrue()
    }

    @Test fun newCalculationIncludingLateDosesCanProceedWithoutTimer() {
        val late = bolus(created = now - 1)
        assertThat(BolusInputSnapshot.from(input(late))!!.matches(listOf(late), now + 1)).isTrue()
    }

    @Test fun unknownInputsAndClockRollbackDoNotAuthorizeDose() {
        assertThat(BolusInputSnapshot.from(IobTotal(now))).isNull()
        assertThat(BolusInputSnapshot.from(input())!!.matches(emptyList(), now-1)).isFalse()
    }

    @Test fun snapshotDoesNotChangeWhenSourceListIsChanged() {
        val list = mutableListOf(stamp(bolus()))
        val snapshot = BolusInputSnapshot.from(IobTotal(now, bolusInputs = list, bolusInputsSince = since))!!
        list.clear()
        assertThat(snapshot.matches(listOf(bolus()), now+1)).isTrue()
    }

    @Test fun databaseReadFailureDoesNotAuthorizeDose() {
        val persistence = mock<PersistenceLayer>()
        whenever(persistence.getBolusesFromTime(any(), any())).thenReturn(Single.error(IllegalStateException("offline")))
        assertThat(BolusInputSnapshot.from(input())!!.validationError(persistence, now+1)).isNotNull()
    }
}
