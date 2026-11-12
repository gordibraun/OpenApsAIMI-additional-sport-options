package app.aaps.core.objects.wizard

import kotlin.math.min

/**
 * Carbs added while the bolus of an earlier meal is still on board.
 *
 * The wizard leaves out of COB the grams it already covered ("handled"), so that they are not dosed
 * twice. The insulin given for those grams is still on board, though, and held against new carbs in
 * full it cancelled their dose: a second helping minutes after a meal bolus came out at zero (34 of 62
 * such calculations over two weeks of November 2026, the owner adding the insulin by eye).
 *
 * So the insulin on board is first set against the carbs it was given for. What the still unabsorbed
 * handled carbs need stays with them; only the rest is held against the new carbs.
 *
 * The credit is bounded on every side:
 *  - it only fills the hole that insulin on board makes in the non-carb part of the calculation, never
 *    beyond zero: the new carbs get at most their own dose from it, never a correction on top;
 *  - it never exceeds the insulin on board left after the carbs the wizard still doses have had theirs;
 *  - a sugar that is low, or will be within a quarter of an hour at the present fall, gets none: there
 *    the insulin on board is winning and the carbs are eaten to stop it.
 */
object EarlierMealInsulin {

    /** 4.4 mmol/L: below it, now or shortly, nothing is added on top of insulin on board. */
    const val LOW_GLUCOSE_MGDL = 80.0

    /** How far the present fall is carried forward: three 5-minute readings. */
    private const val FALL_READINGS_AHEAD = 3.0

    /**
     * @param newCarbs grams being entered now
     * @param handledCarbInsulin units the covered, still unabsorbed carbs of earlier meals need
     * @param insulinOnBoard units the wizard subtracts for bolus and basal insulin on board
     * @param insulinFromCob units the wizard adds for carbs on board it has not covered yet
     * @param otherInsulin the glucose, trend and superbolus parts together (the owner's own correction excluded)
     * @param glucoseMgdl the sugar the calculation uses, 0 when there is none
     * @param shortAvgDeltaMgdl its change per 5 minutes
     * @param activityFactor the share of new insulin a present activity leaves (1 = none)
     * @return units of insulin on board left with the earlier meal, to be added back to the total
     */
    fun credit(
        newCarbs: Int,
        handledCarbInsulin: Double,
        insulinOnBoard: Double,
        insulinFromCob: Double,
        otherInsulin: Double,
        glucoseMgdl: Double,
        shortAvgDeltaMgdl: Double,
        activityFactor: Double = 1.0
    ): Double {
        if (newCarbs <= 0) return 0.0
        if (!handledCarbInsulin.isFinite() || handledCarbInsulin <= 0.0) return 0.0
        if (!insulinOnBoard.isFinite() || !insulinFromCob.isFinite() || !otherInsulin.isFinite()) return 0.0
        if (!glucoseMgdl.isFinite() || glucoseMgdl <= 0.0) return 0.0
        val fall = if (shortAvgDeltaMgdl.isFinite()) min(0.0, shortAvgDeltaMgdl) else 0.0
        if (glucoseMgdl + FALL_READINGS_AHEAD * fall < LOW_GLUCOSE_MGDL) return 0.0

        val stillNeeded = handledCarbInsulin * (if (activityFactor.isFinite()) activityFactor.coerceIn(0.0, 1.0) else 1.0)
        val beyondDosedCob = insulinOnBoard - insulinFromCob
        val hole = beyondDosedCob - otherInsulin
        return minOf(stillNeeded, beyondDosedCob, hole).coerceAtLeast(0.0)
    }
}
