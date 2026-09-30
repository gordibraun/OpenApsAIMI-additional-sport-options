package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.insulin.Insulin
import kotlin.math.min

/** The active insulin plugin's unit-dose curve, sampled once per decision. No food inputs. */
class PlannedInsulinAction private constructor(private val actedFraction: DoubleArray) {

    fun effectsPer5Minutes(smbUnits: Double, basalDeltaUph: Double, durationMinutes: Int, steps: Int): DoubleArray {
        require(smbUnits.isFinite() && smbUnits >= 0.0 && basalDeltaUph.isFinite())
        require(durationMinutes >= 0 && steps >= 0 && steps * 2 < actedFraction.size)

        fun fractionAt(ageMinutes: Double): Double {
            if (ageMinutes <= 0.0) return 0.0
            val index = ageMinutes / 2.5
            val lower = index.toInt()
            val upper = min(lower + 1, actedFraction.lastIndex)
            return actedFraction[lower] + (actedFraction[upper] - actedFraction[lower]) * (index - lower)
        }

        fun cumulativeEffect(elapsedMinutes: Int): Double {
            var effect = smbUnits * fractionAt(elapsedMinutes.toDouble())
            // Like AAPS temporary-basal accounting, use small doses at interval midpoints.
            // Only insulin scheduled before this forecast point can have acted by then.
            var start = 0
            while (start < min(durationMinutes, elapsedMinutes)) {
                val end = min(start + 5, min(durationMinutes, elapsedMinutes))
                val deliveredUnits = basalDeltaUph * (end - start) / 60.0
                effect += deliveredUnits * fractionAt(elapsedMinutes - (start + end) / 2.0)
                start = end
            }
            return effect
        }

        var previous = 0.0
        return DoubleArray(steps) { index ->
            val cumulative = cumulativeEffect((index + 1) * 5)
            (cumulative - previous).also { previous = cumulative }
        }
    }

    companion object {
        fun from(insulin: Insulin, diaHours: Double, horizonMinutes: Int = 240): PlannedInsulinAction {
            require(diaHours.isFinite() && diaHours > 0.0 && horizonMinutes in 5..720)
            val unitDose = BS(timestamp = 0L, amount = 1.0, type = BS.Type.NORMAL)
            var previous = 0.0
            val fractions = DoubleArray(horizonMinutes * 2 / 5 + 1) { index ->
                val remaining = insulin.iobCalcForTreatment(unitDose, index * 150_000L, diaHours).iobContrib
                require(remaining.isFinite() && remaining in -1e-6..1.000001) { "Invalid insulin action curve" }
                val fraction = (1.0 - remaining).coerceIn(0.0, 1.0)
                require(fraction + 1e-6 >= previous && (index != 0 || fraction < 1e-6)) { "Non-monotonic insulin action curve" }
                fraction.coerceAtLeast(previous).also { previous = it }
            }
            // Do not normalize to the forecast horizon: insulin can remain active beyond it.
            return PlannedInsulinAction(fractions)
        }
    }
}
