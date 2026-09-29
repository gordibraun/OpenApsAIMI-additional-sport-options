package app.aaps.core.objects.wizard

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WizardDeliveryReceiptTest {
    @Test fun carbsOnlyNeedNoInsulinReceipt() {
        val calls = mutableListOf<Triple<Boolean, String, Double>>()
        val receipt = WizardDeliveryReceipt(0.0, 3) { a, b, c -> calls += Triple(a, b, c) }
        receipt.carbs(true, "stored")
        receipt.carbs(true, "duplicate")
        assertEquals(listOf(Triple(true, "stored", 0.0)), calls)
    }

    @Test fun combinedTreatmentWaitsForBothReceiptsInEitherOrder() {
        for (carbsFirst in listOf(true, false)) {
            val calls = mutableListOf<Double>()
            val receipt = WizardDeliveryReceipt(1.0, 10) { success, _, amount -> assertTrue(success); calls += amount }
            if (carbsFirst) receipt.carbs(true, "stored") else receipt.insulin(true, "delivered", 0.9)
            assertTrue(calls.isEmpty())
            if (carbsFirst) receipt.insulin(true, "delivered", 0.9) else receipt.carbs(true, "stored")
            assertEquals(listOf(0.9), calls)
            receipt.insulin(true, "duplicate", 0.9)
            assertEquals(1, calls.size)
        }
    }

    @Test fun failureCannotBecomeSuccessOrCauseDuplicateReport() {
        val calls = mutableListOf<Boolean>()
        val receipt = WizardDeliveryReceipt(1.0, 10) { success, _, _ -> calls += success }
        receipt.insulin(false, "partial", 0.2)
        receipt.carbs(true, "stored")
        receipt.rejected("rejected")
        assertEquals(listOf(false), calls)
    }
}
