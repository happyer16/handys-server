package co.handys.booking.payment.application

import co.handys.booking.payment.fake.FakeIdempotencyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdempotencyStoreTest {
    @Test
    fun `second begin returns first in-flight entry`() {
        val store = FakeIdempotencyStore()
        val a = store.begin("pay:r1:CHARGE_FULL")
        assertIs<BeginResult.Acquired>(a)
        val b = store.begin("pay:r1:CHARGE_FULL")
        val existing = assertIs<BeginResult.Existing>(b)
        assertEquals("pay:r1:CHARGE_FULL", existing.entry.key)
        assertNull(existing.entry.payload)
        assertEquals(false, existing.entry.terminal)
        assertTrue(existing.entry.inFlight)
    }

    @Test
    fun `begin after terminal complete returns stored payload`() {
        val store = FakeIdempotencyStore()
        assertIs<BeginResult.Acquired>(store.begin("pay:r1:CHARGE_FULL"))
        store.complete("pay:r1:CHARGE_FULL", responsePayload = """{"pgPaymentId":"pg_1"}""", terminal = true)

        val again = assertIs<BeginResult.Existing>(store.begin("pay:r1:CHARGE_FULL"))
        assertTrue(again.entry.terminal)
        assertEquals("""{"pgPaymentId":"pg_1"}""", again.entry.payload)
    }

    @Test
    fun `begin after non-terminal complete re-acquires the key`() {
        val store = FakeIdempotencyStore()
        assertIs<BeginResult.Acquired>(store.begin("pay:r1:CHARGE_FULL"))
        store.complete("pay:r1:CHARGE_FULL", responsePayload = """{"reason":"declined"}""", terminal = false)

        assertIs<BeginResult.Acquired>(store.begin("pay:r1:CHARGE_FULL"))
        // Re-acquired means in flight again, so a concurrent caller is held off.
        val concurrent = assertIs<BeginResult.Existing>(store.begin("pay:r1:CHARGE_FULL"))
        assertTrue(concurrent.entry.inFlight)
        assertNull(concurrent.entry.payload)
    }
}
