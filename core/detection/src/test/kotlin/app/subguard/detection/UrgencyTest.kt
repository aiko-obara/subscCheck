package app.subguard.detection

import app.subguard.detection.model.Urgency
import app.subguard.detection.model.urgencyOf
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class UrgencyTest {

    private val now: Instant = Instant.parse("2026-08-07T09:41:00Z")

    private fun inHours(h: Long) = now.plus(Duration.ofHours(h))

    @Test
    fun `more than seven days out is safe`() {
        assertEquals(Urgency.SAFE, urgencyOf(inHours(24 * 18), now))
        assertEquals(Urgency.SAFE, urgencyOf(inHours(25), now))
    }

    @Test
    fun `within 24 hours is soon`() {
        assertEquals(Urgency.SOON, urgencyOf(inHours(24), now))
        assertEquals(Urgency.SOON, urgencyOf(inHours(21), now))
        assertEquals(Urgency.SOON, urgencyOf(inHours(4), now))
    }

    @Test
    fun `within 3 hours is critical`() {
        assertEquals(Urgency.CRITICAL, urgencyOf(inHours(3), now))
        assertEquals(Urgency.CRITICAL, urgencyOf(inHours(1), now))
    }

    /**
     * 締切を過ぎていても CRITICAL。
     * 「過ぎたので安全」ではなく「もう課金されているかもしれない」状態であり、
     * ユーザーに最も強く見せるべき。
     */
    @Test
    fun `past due stays critical`() {
        assertEquals(Urgency.CRITICAL, urgencyOf(inHours(-1), now))
        assertEquals(Urgency.CRITICAL, urgencyOf(inHours(-240), now))
    }

    /** 通知のオフセットは緊急度のしきい値と一致していること。 */
    @Test
    fun `reminder offsets match the urgency thresholds`() {
        assertEquals(listOf(24L, 3L), Urgency.REMINDER_OFFSETS_HOURS)
        assertEquals(Duration.ofHours(24), Urgency.SOON_THRESHOLD)
        assertEquals(Duration.ofHours(3), Urgency.CRITICAL_THRESHOLD)
    }
}
