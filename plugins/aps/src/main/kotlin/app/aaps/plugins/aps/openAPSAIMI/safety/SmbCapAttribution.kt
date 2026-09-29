package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.abs

internal data class SmbCapAttribution(val units: Double, val reason: String) {
    companion object {
        fun bindingReasons(caps: List<SmbCapAttribution>, proposed: Double, result: Double): List<String> =
            if (result >= proposed) emptyList()
            else caps.filter { abs(it.units - result) < 1e-5 }.map { it.reason }.distinct()
    }
}
