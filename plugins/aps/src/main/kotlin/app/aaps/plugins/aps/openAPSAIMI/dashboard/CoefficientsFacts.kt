package app.aaps.plugins.aps.openAPSAIMI.dashboard

/** Everything the dashboard shows, gathered by the plugin from the learner, the ISF history, the last run and the records. */
class CoefficientsFacts(
    val nowEpochMs: Long,
    /** The sensitivity the wizard corrects by ("Е"): the average of the loop's decision ISF over the last hours. */
    val foodIsf: Double?,
    /** The last run's own sensitivity ("Р"). */
    val decisionIsf: Double?,
    val profileIsf: Double,
    val isfSamples: List<IsfPoint>,
    /** The carb ratio in use now, and the profile's. */
    val icApplied: Double,
    val icProfile: Double,
    val learnerEnabled: Boolean,
    val lessons: List<BucketLesson>,
    val mealsEvaluated: Int,
    val mealsWithResult: Int,
    val meals: List<MealRow>,
    val wizard: Consumer?,
    val loop: Consumer?,
    val watch: WatchConsumer?
) {
    class IsfPoint(val atEpochMs: Long, val isf: Double, val glucose: Double)
    /** [meals] is what the lesson rests on (the whole day's when [fromAllDay]); [ownMeals] the meals of this part of the day alone. */
    class BucketLesson(val label: String, val lessonIc: Double?, val meals: Int, val fromAllDay: Boolean, val appliedIc: Double?, val current: Boolean, val ownMeals: Int = meals)
    class MealRow(val atEpochMs: Long, val grams: Double, val givenU: Double, val neededU: Double, val icEff: Double?, val bgStart: Double, val bgEnd: Double)
    class Consumer(val atEpochMs: Long, val isf: Double, val ic: Double)
    class WatchConsumer(val atEpochMs: Long, val isf: Double, val foodIsf: Double?, val ic: Double)
}
