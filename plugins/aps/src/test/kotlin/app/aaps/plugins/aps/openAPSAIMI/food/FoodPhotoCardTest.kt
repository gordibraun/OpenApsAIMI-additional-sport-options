package app.aaps.plugins.aps.openAPSAIMI.food

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A glance at a card must answer three things: what the server thinks, what I can do, and what
 * has already happened. These tests read the words the owner would read.
 */
class FoodPhotoCardTest {

    private val time: (Long) -> String = { "12:40" }

    private val serverAnswer = JSONObject(
        """{"carbs_g": 40, "carbs_range": [30, 50], "fat_g": 12, "protein_g": 8,
            "items": [{"name": "тост бриошь", "portion": "2 куска", "carbs_g": 30}, {"name": "джем", "portion": "1 ст. л.", "carbs_g": 10}],
            "absorption": "slow", "confidence": "medium", "advice": "Введите сразу, тип еды — медленная."}"""
    )

    private fun entry(status: FoodPhotoEntry.Status, estimate: FoodEstimate? = FoodEstimate.fromJson(serverAnswer), own: Double? = null) =
        FoodPhotoEntry(id = "abcdef123456", atEpochMs = 0, photoPath = "/x.jpg", caption = "завтрак", status = status, serverId = if (estimate != null) "s1" else null, estimate = estimate, finalGrams = own)

    @Test
    fun `an estimated plate says the grams first and offers eat, correct, decline`() {
        val v = FoodPhotoCard.of(entry(FoodPhotoEntry.Status.ESTIMATED), false, time)
        assertEquals("12:40 · «завтрак»", v.header)
        assertEquals("Оценено", v.chip)
        assertEquals("≈ 40 г углеводов", v.headline)
        assertEquals("от 30 до 50 г", v.subline)
        assertEquals(listOf("• тост бриошь, 2 куска — 30 г", "• джем, 1 ст. л. — 10 г"), v.items)
        assertEquals("медленная еда (жир/белок) · жиры ~12 г · белки ~8 г · уверенность средняя", v.composition)
        assertEquals(listOf(FoodPhotoCard.Action.EAT, FoodPhotoCard.Action.CORRECT, FoodPhotoCard.Action.DECLINE), v.actions)
        assertEquals("✅ Ем: 40 г в калькулятор", FoodPhotoCard.actionLabel(v.actions.first(), v.gramsToEnter))
    }

    @Test
    fun `the owner's own number replaces the estimate everywhere that matters`() {
        val v = FoodPhotoCard.of(entry(FoodPhotoEntry.Status.ESTIMATED, own = 55.0), false, time)
        assertEquals("Вы поправили", v.chip)
        assertEquals("55 г углеводов — ваша цифра", v.headline)
        assertEquals("оценка была ≈ 40 г углеводов", v.subline)
        assertEquals(55, v.gramsToEnter)
        assertEquals("✅ Ем: 55 г в калькулятор", FoodPhotoCard.actionLabel(FoodPhotoCard.Action.EAT, v.gramsToEnter))
    }

    @Test
    fun `a photo waiting for the network says so and can still be eaten by hand`() {
        val waiting = FoodPhotoCard.of(entry(FoodPhotoEntry.Status.QUEUED, estimate = null), false, time)
        assertEquals("Ждёт сети", waiting.chip)
        assertTrue(waiting.headline.contains("интернет"))
        assertFalse(waiting.actions.contains(FoodPhotoCard.Action.EAT), "no grams, nothing to eat yet")
        assertTrue(waiting.actions.contains(FoodPhotoCard.Action.CORRECT), "but the owner can type the grams")

        val sending = FoodPhotoCard.of(entry(FoodPhotoEntry.Status.QUEUED, estimate = null), true, time)
        assertEquals("Оцениваю…", sending.chip)

        val typed = FoodPhotoCard.of(entry(FoodPhotoEntry.Status.QUEUED, estimate = null, own = 30.0), false, time)
        assertEquals(FoodPhotoCard.Action.EAT, typed.actions.first())
        assertEquals(30, typed.gramsToEnter)
    }

    @Test
    fun `an eaten plate shows what went to the wizard and only offers undo`() {
        val e = entry(FoodPhotoEntry.Status.EATEN).apply { decidedAtEpochMs = 1 }
        val v = FoodPhotoCard.of(e, false, time)
        assertEquals("Съедено", v.chip)
        assertEquals(FoodPhotoCard.Tone.GOOD, v.chipTone)
        assertEquals("40 г отправлены в калькулятор", v.headline)
        assertEquals("в 12:40, в заметке болюса: фото 123456", v.subline)
        assertEquals(listOf(FoodPhotoCard.Action.UNDO), v.actions)
    }

    @Test
    fun `a declined plate is plainly not a meal`() {
        val v = FoodPhotoCard.of(entry(FoodPhotoEntry.Status.DECLINED), false, time)
        assertEquals("Не ел", v.chip)
        assertEquals("Фото без еды: в расчёты не попадёт", v.headline)
        assertTrue(v.items.isEmpty())
        assertEquals(listOf(FoodPhotoCard.Action.UNDO, FoodPhotoCard.Action.DELETE), v.actions)
    }

    @Test
    fun `a refused photo names the problem and offers retry, own number, delete`() {
        val e = entry(FoodPhotoEntry.Status.ERROR, estimate = null).apply { error = "сервер не принял токен — проверьте настройки" }
        val v = FoodPhotoCard.of(e, false, time)
        assertEquals("Не вышло", v.chip)
        assertEquals(FoodPhotoCard.Tone.WARN, v.chipTone)
        assertEquals("сервер не принял токен — проверьте настройки", v.problem)
        assertEquals(listOf(FoodPhotoCard.Action.RETRY, FoodPhotoCard.Action.CORRECT, FoodPhotoCard.Action.DELETE), v.actions)
    }

    @Test
    fun `every label is short enough for a phone button`() {
        FoodPhotoCard.Action.entries.forEach { a ->
            assertTrue(FoodPhotoCard.actionLabel(a, 40).length <= 28, "label too long: ${FoodPhotoCard.actionLabel(a, 40)}")
        }
    }

    @Test
    fun `entries survive the trip through the json file`() {
        val e = entry(FoodPhotoEntry.Status.EATEN, own = 44.0).apply { decisionPending = true; decidedAtEpochMs = 5; error = null }
        val back = FoodPhotoEntry.listFromJson(JSONObject(FoodPhotoEntry.listToJson(listOf(e)).toString())).single()
        assertEquals(e.id, back.id)
        assertEquals(FoodPhotoEntry.Status.EATEN, back.status)
        assertEquals("s1", back.serverId)
        assertEquals(44.0, back.finalGrams)
        assertEquals(40.0, back.estimate?.carbsG)
        assertEquals(2, back.estimate?.items?.size)
        assertEquals("slow", back.estimate?.absorption)
        assertTrue(back.decisionPending)
        assertEquals(5L, back.decidedAtEpochMs)
        assertNull(back.error)
        assertFalse(back.needsUpload)
    }

    @Test
    fun `what the server still has to hear`() {
        assertTrue(entry(FoodPhotoEntry.Status.QUEUED, estimate = null).needsUpload)
        assertTrue(entry(FoodPhotoEntry.Status.EATEN, estimate = null).needsUpload, "eaten before the estimate: still worth the record")
        assertFalse(entry(FoodPhotoEntry.Status.DECLINED, estimate = null).needsUpload, "declined before upload: never send")
        assertFalse(entry(FoodPhotoEntry.Status.ERROR, estimate = null).needsUpload, "refused: only a retry sends again")
        assertFalse(entry(FoodPhotoEntry.Status.ESTIMATED).needsUpload)
    }

    @Test
    fun `an answer without a carbohydrate number is no estimate`() {
        assertNull(FoodEstimate.fromJson(JSONObject("""{"items": []}""")))
        assertNull(FoodEstimate.fromJson(null))
        val e = FoodEstimate.fromJson(JSONObject("""{"carbs_g": 12.4}"""))!!
        assertEquals(12.4, e.carbsG)
        assertNull(e.rangeLowG)
        assertTrue(e.items.isEmpty())
    }
}
