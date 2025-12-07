package app.aaps.core.data.ue

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT

// единое описания значения + единица измерения для разных UserEntry-событий
sealed class ValueWithUnit {

    object UNKNOWN : ValueWithUnit() // fallback

    data class SimpleString(val value: String) : ValueWithUnit()

    data class SimpleInt(val value: Int) : ValueWithUnit()

    data class Mgdl(val value: Double) : ValueWithUnit()

    data class Mmoll(val value: Double) : ValueWithUnit()

    data class Timestamp(val value: Long) : ValueWithUnit()

    data class Insulin(val value: Double) : ValueWithUnit()

    data class UnitPerHour(val value: Double) : ValueWithUnit()

    data class Gram(val value: Int) : ValueWithUnit()

    data class Minute(val value: Int) : ValueWithUnit()

    data class Hour(val value: Int) : ValueWithUnit()

    data class Percent(val value: Int) : ValueWithUnit()

    data class TEType(val value: TE.Type) : ValueWithUnit()

    data class TEMeterType(val value: TE.MeterType) : ValueWithUnit()

    // добавлено Мэтью
    data class TELocation(val value: TE.Location) : ValueWithUnit()

    data class TEArrow(val value: TE.Arrow) : ValueWithUnit()

    data class TETTReason(val value: TT.Reason) : ValueWithUnit()

    // режим Rotation Manager
    data class RMMode(val value: RM.Mode) : ValueWithUnit()

    companion object {

        fun fromGlucoseUnit(value: Double, glucoseUnit: GlucoseUnit): ValueWithUnit =
            when (glucoseUnit) {
                GlucoseUnit.MGDL -> Mgdl(value)
                GlucoseUnit.MMOL -> Mmoll(value)
            }
    }
}