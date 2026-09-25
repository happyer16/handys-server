package co.handys.booking.payment.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant

class PaymentIntentTest {
    @Test
    fun `Succeeded is terminal - second markSucceeded throws`() {
        val intent = PaymentIntent.create(
            id = "pi_1",
            reservationId = "r1",
            amountWon = 100_000L,
            idempotencyKey = IdempotencyKeys.chargeFull("r1"),
            expiresAt = Instant.parse("2026-09-25T12:15:00Z"),
            now = Instant.parse("2026-09-25T12:00:00Z"),
        ).markProcessing(Instant.parse("2026-09-25T12:01:00Z"))
            .markSucceeded(Instant.parse("2026-09-25T12:02:00Z"))

        assertFailsWith<IllegalStateException> {
            intent.markSucceeded(Instant.parse("2026-09-25T12:03:00Z"))
        }
    }

    @Test
    fun `charge key is business-scoped not random`() {
        assertEquals("pay:r1:CHARGE_FULL", IdempotencyKeys.chargeFull("r1"))
    }
}
