package app.aaps.core.objects.wizard

/** Carb storage and pump delivery are separate receipts, not interchangeable successes. */
internal class WizardDeliveryReceipt(insulin: Double, carbs: Int, private val report: (Boolean, String, Double) -> Unit) {
    private var insulinDone = insulin == 0.0
    private var carbsDone = carbs == 0
    private var delivered = 0.0
    private var reported = false

    @Synchronized fun insulin(success: Boolean, comment: String, amount: Double) {
        delivered = amount.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        insulinDone = success
        finish(success, comment)
    }

    @Synchronized fun carbs(success: Boolean, comment: String) {
        carbsDone = success
        finish(success, comment)
    }

    @Synchronized fun rejected(comment: String) = finish(false, comment)

    private fun finish(success: Boolean, comment: String) {
        if (!reported && (!success || (insulinDone && carbsDone))) {
            reported = true
            report(success, comment, delivered)
        }
    }
}
