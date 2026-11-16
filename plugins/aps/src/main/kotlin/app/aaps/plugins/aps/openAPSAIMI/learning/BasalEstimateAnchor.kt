package app.aaps.plugins.aps.openAPSAIMI.learning

import java.util.Locale

/**
 * What AIMI takes for "this person's basal" when it sizes its own additions on a rise.
 *
 * AIMI starts from the week's daily dose divided by body weight and multiplies that by what [BasalLearner]
 * has learned. The learner was to find the multiplier from glucose drifting while nothing else acts. In a closed
 * loop nothing of the kind exists: the loop itself keeps glucose level, so before 29.09.2026 the learner took
 * the loop's own doing for basal need and ran up to its ceiling, and since then its rule of admission - no bolus
 * insulin on board, no temporary basal, no carbohydrates within the insulin's action - is never met. Its last
 * reading is of 28.10.2026, and the x1.99 it stood at then went on multiplying every decision.
 *
 * That frozen number happens to give 1.09 U/h for this owner, his profile's mean basal almost to the hundredth -
 * so setting the multiplier back to one, as first proposed, would have halved AIMI's figure. What is wrong is
 * not the value but that nothing holds it: it does not follow the profile when the profile is corrected, and a
 * single admitted reading may move it anywhere between 0.7 and 2.5.
 *
 * So the figure is held to the one thing all the insulin accounting is anchored to already: the profile's basal,
 * taken as its mean over the day, which is what a dose-per-weight figure stands for. Basal adaptation itself
 * belongs in the profile, hour by hour, from nights without food and the insulin actually delivered; this object
 * only makes AIMI's figure follow it. With the switch off, the learner's number is used as before.
 */
object BasalEstimateAnchor {

    data class Decision(
        /** What the dose-per-weight figure is multiplied by. */
        val multiplier: Double,
        val anchored: Boolean,
        /** One line for the loop's reason and the trials screen. */
        val line: String
    )

    /** The same bounds the learner's own multiplier is held to. */
    const val MIN_MULTIPLIER = 0.70
    const val MAX_MULTIPLIER = 2.50

    /**
     * @param anchorEnabled the trial's switch
     * @param fromDoseUph the week's daily dose over body weight, U/h
     * @param profileMeanUph the mean of the profile's basal over the day, U/h, or null when no profile is known
     * @param learnedMultiplier what the learner holds
     * @param learnerLastObservedAtEpochMs when it last took a reading in, 0 for never
     */
    fun decide(
        anchorEnabled: Boolean,
        fromDoseUph: Double,
        profileMeanUph: Double?,
        learnedMultiplier: Double,
        learnerLastObservedAtEpochMs: Long,
        nowEpochMs: Long
    ): Decision {
        val idleDays = if (learnerLastObservedAtEpochMs > 0L) (nowEpochMs - learnerLastObservedAtEpochMs) / 86_400_000L else null
        val learnerSays = "учитель базала: x%.2f, ".format(Locale.US, learnedMultiplier) +
            (idleDays?.let { if (it >= 1) "не учился $it дн." else "учился сегодня" } ?: "не учился ни разу")
        val usable = profileMeanUph != null && profileMeanUph.isFinite() && profileMeanUph > 0.0 && fromDoseUph.isFinite() && fromDoseUph > 0.0
        if (!anchorEnabled || !usable)
            return Decision(
                learnedMultiplier, anchored = false,
                line = "Basal adjusted by learner (x%.2f)".format(Locale.US, learnedMultiplier) +
                    (if (anchorEnabled) " — профиль неизвестен, привязка к нему невозможна; $learnerSays" else "; $learnerSays")
            )
        val multiplier = (profileMeanUph!! / fromDoseUph).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        return Decision(
            multiplier, anchored = true,
            line = "🧪 Базал AIMI %.2f ЕД/ч держится за средний базал профиля %.2f ЕД/ч (доза/вес %.2f × %.2f); $learnerSays, не используется"
                .format(Locale.US, fromDoseUph * multiplier, profileMeanUph, fromDoseUph, multiplier)
        )
    }
}
