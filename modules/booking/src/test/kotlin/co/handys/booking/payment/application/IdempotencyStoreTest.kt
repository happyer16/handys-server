package co.handys.booking.payment.application

import co.handys.booking.payment.infrastructure.InMemoryIdempotencyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdempotencyStoreTest {
    @Test
    fun `second begin returns first in-flight entry`() {
        val store = InMemoryIdempotencyStore()
        val a = store.begin("pay:r1:CHARGE_FULL")
        assertIs<BeginResult.Acquired>(a)
        val b = store.begin("pay:r1:CHARGE_FULL")
        val existing = assertIs<BeginResult.Existing>(b)
        assertEquals("pay:r1:CHARGE_FULL", existing.entry.key)
        assertNull(existing.entry.payload)
        assertEquals(false, existing.entry.terminal)
    }

    @Test
    fun `begin after terminal complete returns stored payload`() {
        val store = InMemoryIdempotencyStore()
        assertIs<BeginResult.Acquired>(store.begin("pay:r1:CHARGE_FULL"))
        store.complete("pay:r1:CHARGE_FULL", responsePayload = """{"pgPaymentId":"pg_1"}""", terminal = true)

        val again = assertIs<BeginResult.Existing>(store.begin("pay:r1:CHARGE_FULL"))
        assertTrue(again.entry.terminal)
        assertEquals("""{"pgPaymentId":"pg_1"}""", again.entry.payload)
    }
}
