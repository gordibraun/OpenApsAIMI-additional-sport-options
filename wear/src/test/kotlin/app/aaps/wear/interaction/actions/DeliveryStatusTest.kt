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
}
