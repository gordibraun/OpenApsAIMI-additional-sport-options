package app.aaps.pump.combowatch.regulation

import app.aaps.pump.combowatch.protocol.RegulationSnapshot
import kotlin.math.pow

/** A test day: readings, a snapshot and a pump record, with defaults that mean "nothing is going on". */
internal class Fixture(val now: Long = 1_800_000_000_000L) {

    /** 1.2 U/h around the clock, so that percentages are easy to read. */
    var basal: List<Double> = List(24) { 1.2 }
    var readings: List<GlucoseReading> = emptyList()
    var snapshot: RegulationSnapshot? = snapshot()
    var tbrs: List<TbrSegment> = emptyList()
    var boluses: List<BolusRecord> = emptyList()
    var activities: List<ActivityRecord> = emptyList()

    fun minutesAgo(minutes: Double): Long = now - (minutes * 60_000).toLong()

    /** Readings every five minutes ending now at [last], having changed by [per5] each step. */
    fun glucose(last: Double, per5: Double = 0.0, count: Int = 9) = apply {
        readings = List(count) { GlucoseReading(minutesAgo(it * 5.0), last - per5 * it) }
    }

    fun snapshot(
        ageMinutes: Double = 2.0,
        target: Double = 117.0,
        hypoThreshold: Double = 70.0,
        sensitivity: Double = 50.0,
        carbRatio: Double = 10.0,
        cob: Double = 0.0,
        /** Units given [bolusAgeAtSnapshot] minutes before the snapshot and still acting. */
        bolusUnits: Double = 0.0,
        bolusAgeAtSnapshot: Double = 20.0,
        assumedTbr: RegulationSnapshot.AssumedTbr? = null,
        validForMinutes: Double = 24 * 60.0
    ): RegulationSnapshot {
        val madeAt = minutesAgo(ageMinutes)
        return RegulationSnapshot(
            madeAtEpochMs = madeAt,
            pumpSerial = "PUMP_TEST",
            validUntilEpochMs = madeAt + (validForMinutes * 60_000).toLong(),
            targetMgdl = target,
            hypoThresholdMgdl = hypoThreshold,
            sensitivityMgdlPerU = sensitivity,
            carbRatioGPerU = carbRatio,
            cobG = cob,
            iobU = bolusUnits * remaining(bolusAgeAtSnapshot),
            insulinActivity = List(48) { bolusUnits * activityPerMinute(bolusAgeAtSnapshot + it * 5.0) },
            insulinRemaining = CURVE,
            assumedTbr = assumedTbr
        )
    }

    fun inputs() = WatchRegulator.Inputs(
        nowEpochMs = now,
        readings = readings,
        snapshot = snapshot,
        pumpBasalUph = basal,
        delivery = DeliveryLog(tbrs, boluses, activities = activities),
        hourOfDay = { 12 }
    )

    fun decide() = WatchRegulator().decide(inputs())

    companion object {

        /** An insulin that is gone after five hours; the shape matters less than that it is one curve. */
        fun remaining(minutes: Double): Double = if (minutes >= 300.0) 0.0 else if (minutes <= 0.0) 1.0 else (1.0 - (minutes / 300.0).pow(1.6)).coerceIn(0.0, 1.0)

        fun activityPerMinute(minutes: Double): Double = (remaining(minutes) - remaining(minutes + 1.0)).coerceAtLeast(0.0)

        /** Sampled every 2.5 minutes for eight hours, as the phone sends it. */
        val CURVE: List<Double> = List(193) { remaining(it * 2.5) }
    }
}
