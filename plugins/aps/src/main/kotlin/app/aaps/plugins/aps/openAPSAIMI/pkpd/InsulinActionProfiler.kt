package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi

data class IobActionProfile(
    val iobTotal: Double,
    val peakMinutes: Double,      // Temps pondéré jusqu'au pic. Négatif si le pic est passé.
    val activityNow: Double,      // Activité relative actuelle (0..1)
    val activityIn30Min: Double   // Activité relative projetée dans 30 min (0..1)
)

object InsulinActionProfiler {
    @Suppress("UNUSED_PARAMETER")
    fun calculate(iobArray: Array<IobTotal>, profile: OapsProfileAimi): IobActionProfile {
        if (iobArray.isEmpty()) {
            return IobActionProfile(0.0, 0.0, 0.0, 0.0)
        }

        val baseline = iobArray.first()
        val totalIob = baseline.iob.coerceAtLeast(0.0)
        if (totalIob == 0.0) {
            return IobActionProfile(0.0, 0.0, 0.0, 0.0)
        }

        val now = baseline.time
        val maxEntry = iobArray.maxByOrNull { it.activity } ?: baseline
        val maxActivity = maxEntry.activity.coerceAtLeast(0.0)
        if (maxActivity <= 0.0) {
            return IobActionProfile(totalIob, 0.0, 0.0, 0.0)
        }

        val activityNow = (baseline.activity / maxActivity).coerceIn(0.0, 1.0)
        val activityIn30Entry = iobArray.minByOrNull { kotlin.math.abs(it.time - (now + 30 * 60_000L)) } ?: baseline
        val activityIn30Min = (activityIn30Entry.activity / maxActivity).coerceIn(0.0, 1.0)
        val peakMinutes = ((maxEntry.time - now) / 60000.0)

        return IobActionProfile(
            iobTotal = totalIob,
            peakMinutes = peakMinutes,
            activityNow = activityNow,
            activityIn30Min = activityIn30Min
        )
    }
}
