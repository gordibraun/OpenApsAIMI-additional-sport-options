package app.aaps.wear.interaction.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DeliveryStatusTest {

    private val now = 1_000_000_000L
    private fun sent(secondsAgo: Long, decision: String) = DeliveryStatus.Entry(now - secondsAgo * 1000, decision, DeliveryStatus.State.SENT)

    @Test
    fun `nothing waiting gives nothing`() {
        assertNull(DeliveryStatus.pick(emptyList(), "Готово", "Подтверждено инсулина: 2.3 Е.", now))
    }

    @Test
    fun `the only request in flight takes whatever comes`() {
        val one = sent(40, "Подать инсулин: 2.3 Е")
        assertSame(one, DeliveryStatus.pick(listOf(one), "Проверьте телефон", "Помпа не ответила.", now))
    }

    @Test
    fun `a refusal right after a second confirmation answers that confirmation, not the dose still on its way`() {
        // 12.11.2026: 2.3 U confirmed and being delivered; a second calculation confirmed and turned away at once.
        val first = sent(30, "Подать инсулин: 2.3 Е")
        val second = sent(2, "Подать инсулин: 1.4 Е")
        val refusal = "Предыдущая подача ещё не подтверждена или не учтена в расчёте. Инсулин не отправлен.\nПроверьте историю подачи перед повторением."
        assertSame(second, DeliveryStatus.pick(listOf(first, second), "Проверьте телефон", refusal, now))
    }

    @Test
    fun `the delivered dose is matched by its amount`() {
        val first = sent(40, "Подать инсулин: 2.3 Е")
        val second = sent(2, "Подать инсулин: 1.4 Е")
        assertSame(first, DeliveryStatus.pick(listOf(first, second), "Готово", "Углеводы записаны: 20 г.\nПодтверждено инсулина: 2.3 Е.", now))
        assertSame(second, DeliveryStatus.pick(listOf(first, second), "Готово", "Углеводы записаны: 10 г.\nПодтверждено инсулина: 1,4 Е.", now))
    }

    @Test
    fun `equal doses and a late failure go to the oldest, as the queue delivers in order`() {
        val first = sent(90, "Подать инсулин: 1.0 Е")
        val second = sent(40, "Подать инсулин: 1.0 Е")
        assertSame(first, DeliveryStatus.pick(listOf(first, second), "Готово", "Помпа подтвердила выполнение: 1.0 Е.", now))
        assertSame(first, DeliveryStatus.pick(listOf(first, second), "Проверьте телефон", "Помпа не ответила.", now))
    }

    @Test
    fun `the dose line is what is kept from the phone's texts`() {
        assertEquals("Подать инсулин: 2.3 Е", DeliveryStatus.shortDecision("Углеводы: 20 г.\nОбычная еда.\nПодать инсулин: 2.3 Е.\n\nрасчёт…"))
        assertEquals("Подтверждено инсулина: 2.3 Е", DeliveryStatus.shortAnswer("Углеводы записаны: 20 г.\nПодтверждено инсулина: 2.3 Е."))
    }

    @Test
    fun `only a dose of insulin still on its way holds a new calculation back`() {
        val dose = sent(40, "Подать инсулин: 2.3 Е")
        assertSame(dose, DeliveryStatus.doseInFlight(listOf(sent(5, "Прогулка: 30 мин"), dose), now))
        assertNull(DeliveryStatus.doseInFlight(listOf(sent(5, "Записать углеводы: 12 г"), sent(5, "Подать инсулин: 0.0 Е")), now))
        // Longer than a dose can take: the phone has dropped it or its answer was lost.
        assertNull(DeliveryStatus.doseInFlight(listOf(sent(200, "Подать инсулин: 2.3 Е")), now))
        // Answered already.
        assertNull(DeliveryStatus.doseInFlight(listOf(DeliveryStatus.Entry(now - 20_000, "Подать инсулин: 2.3 Е", DeliveryStatus.State.DONE)), now))
    }

    // ---- what the screens tell (20.11.2026) -----------------------------------------------------------

    private fun done(sentSecondsAgo: Long, answeredSecondsAgo: Long, decision: String, answer: String) =
        DeliveryStatus.Entry(now - sentSecondsAgo * 1000, decision, DeliveryStatus.State.DONE, answer, now - answeredSecondsAgo * 1000)

    private fun failed(sentSecondsAgo: Long, answeredSecondsAgo: Long, decision: String, answer: String) =
        DeliveryStatus.Entry(now - sentSecondsAgo * 1000, decision, DeliveryStatus.State.FAILED, answer, now - answeredSecondsAgo * 1000)

    @Test
    fun `a dose just confirmed is on its way, said so that it cannot be taken for a failure`() {
        // 20.11.2026 11:33:03, forty-three seconds after «Подтвердить»: the line read «⏳ Подаётся: Подать инсулин: 2.…».
        val p = DeliveryStatus.progressOf(listOf(sent(43, "Подать инсулин: 2.0 Е")), now)!!
        assertEquals(DeliveryStatus.Phase.IN_FLIGHT, p.phase)
        assertEquals(2.0, p.unitsU!!, 1e-9)
        assertEquals(true, p.isDose)
        assertEquals(true, p.pending)
        assertEquals("⏳ Подаётся 2,0 ЕД", DeliveryStatus.headline(p))
        assertEquals("Доза уже отправлена на помпу, идёт 43 с. Повторять ввод не нужно.", DeliveryStatus.detail(p, now))
        assertEquals("⏳ Подаётся 2,0 ЕД", DeliveryStatus.lineOf(p, now))
    }

    @Test
    fun `the phone's answer turns it into given, or into not given with the reason`() {
        val given = DeliveryStatus.progressOf(listOf(done(101, 0, "Подать инсулин: 2.0 Е", "Подтверждено инсулина: 2.0 Е")), now)!!
        assertEquals(DeliveryStatus.Phase.DONE, given.phase)
        assertEquals(false, given.pending)
        assertEquals("✅ Подано 2,0 ЕД", DeliveryStatus.headline(given))
        assertEquals("Подтверждено инсулина: 2.0 Е", DeliveryStatus.detail(given, now))
        assertEquals("✓ Подтверждено инсулина: 2.0 Е", DeliveryStatus.lineOf(given, now))

        val refused = DeliveryStatus.progressOf(listOf(failed(20, 15, "Подать инсулин: 1.4 Е", "Помпа не ответила")), now)!!
        assertEquals(DeliveryStatus.Phase.FAILED, refused.phase)
        assertEquals("❌ Не подано", DeliveryStatus.headline(refused))
        assertEquals("Помпа не ответила. Инсулин не введён.", DeliveryStatus.detail(refused, now))
        // The phone's refusal of a second calculation says itself that nothing was sent.
        val turnedAway = DeliveryStatus.progressOf(
            listOf(failed(5, 3, "Подать инсулин: 1.4 Е", DeliveryStatus.shortAnswer("Предыдущая подача ещё не подтверждена или не учтена в расчёте. Инсулин не отправлен.\nПроверьте историю подачи перед повторением."))), now
        )!!
        assertEquals("Предыдущая подача ещё не подтверждена или не учтена в расчёте. Инсулин не отправлен.", DeliveryStatus.detail(turnedAway, now))
    }

    @Test
    fun `longer than a dose takes with no answer is said as it is - it may have been given`() {
        val p = DeliveryStatus.progressOf(listOf(sent(240, "Подать инсулин: 2.0 Е")), now)!!
        assertEquals(DeliveryStatus.Phase.NO_ANSWER, p.phase)
        assertEquals(true, p.pending)
        assertEquals("❓ Нет ответа", DeliveryStatus.headline(p))
        assertEquals("Телефон не ответил за 4 мин. Доза 2,0 ЕД могла быть подана. Не вводите её заново, пока не проверите помпу.", DeliveryStatus.detail(p, now))
        assertEquals("❓ Нет ответа о дозе 2,0 ЕД, 4 мин", DeliveryStatus.lineOf(p, now))
    }

    @Test
    fun `a new dose on its way is told before an old one that never got its answer, and an answer before an older silence`() {
        val lost = sent(40 * 60, "Подать инсулин: 1.0 Е")
        val flying = sent(30, "Подать инсулин: 2.3 Е")
        assertEquals("⏳ Подаётся 2,3 ЕД", DeliveryStatus.headline(DeliveryStatus.progressOf(listOf(lost, flying), now)!!))
        // Two confirmed one after the other: the first is the one being delivered, the other waits its turn.
        val two = DeliveryStatus.progressOf(listOf(sent(50, "Подать инсулин: 2.3 Е"), sent(5, "Подать инсулин: 1.0 Е")), now)!!
        assertEquals("⏳ Подаётся 2,3 ЕД (+1 в очереди)", DeliveryStatus.lineOf(two, now))
        // The newer thing wins: a dose given a minute ago over a silence of forty minutes, a fresh silence over an old answer.
        val afterLost = DeliveryStatus.progressOf(listOf(lost, done(160, 60, "Подать инсулин: 2.3 Е", "Подтверждено инсулина: 2.3 Е")), now)!!
        assertEquals(DeliveryStatus.Phase.DONE, afterLost.phase)
        val silentAfterDone = DeliveryStatus.progressOf(listOf(done(900, 800, "Подать инсулин: 1.0 Е", "Подтверждено инсулина: 1.0 Е"), sent(200, "Подать инсулин: 2.3 Е")), now)!!
        assertEquals(DeliveryStatus.Phase.NO_ANSWER, silentAfterDone.phase)
        assertNull(DeliveryStatus.progressOf(emptyList(), now))
    }

    @Test
    fun `carbohydrates or a walk sent through the phone are told too, but are no dose`() {
        val p = DeliveryStatus.progressOf(listOf(sent(3, "Записать углеводы: 12 г")), now)!!
        assertEquals(false, p.isDose)
        assertEquals("⏳ Отправлено телефону", DeliveryStatus.headline(p))
        assertEquals("Записать углеводы: 12 г. Идёт 3 с.", DeliveryStatus.detail(p, now))
        assertEquals("⏳ Отправлено: Записать углеводы: 12 г", DeliveryStatus.lineOf(p, now))
        assertEquals("✅ Готово", DeliveryStatus.headline(DeliveryStatus.progressOf(listOf(done(9, 1, "Записать углеводы: 12 г", "Углеводы записаны: 12 г")), now)!!))
        assertEquals(2.5, DeliveryStatus.unitsIn("Подать инсулин: 2,5 Е")!!, 1e-9)
        assertNull(DeliveryStatus.unitsIn("Прогулка: 30 мин"))
        assertEquals("0,4 ЕД", DeliveryStatus.dose(0.4))
    }
}
