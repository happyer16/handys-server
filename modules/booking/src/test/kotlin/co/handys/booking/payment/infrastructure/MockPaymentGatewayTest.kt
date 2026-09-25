package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.ChargeRequest
import co.handys.booking.payment.application.ChargeResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MockPaymentGatewayTest {
    private val gateway = MockPaymentGateway(delayMillis = 25)

    @AfterTest
    fun tearDown() {
        MockPaymentGateway.resetControls()
    }

    @Test
    fun `default charge succeeds with evt prefix on pgEventId`() {
        val result = assertIs<ChargeResult.Succeeded>(
            gateway.charge(
                ChargeRequest(
                    idempotencyKey = "pay:r1:CHARGE_FULL",
                    amountWon = 50_000,
                    reservationId = "r1",
                ),
            ),
        )
        assertTrue(result.pgEventId.startsWith("evt_"))
        assertTrue(result.pgPaymentId.startsWith("pg_"))
    }

    @Test
    fun `same idempotency key returns stable pg ids`() {
        val request = ChargeRequest(
            idempotencyKey = "pay:r1:CHARGE_FULL",
            amountWon = 50_000,
            reservationId = "r1",
        )
        val first = assertIs<ChargeResult.Succeeded>(gateway.charge(request))
        val second = assertIs<ChargeResult.Succeeded>(gateway.charge(request))
        assertEquals(first.pgPaymentId, second.pgPaymentId)
        assertEquals(first.pgEventId, second.pgEventId)
    }

    @Test
    fun `different idempotency keys get different pg ids`() {
        val a = assertIs<ChargeResult.Succeeded>(
            gateway.charge(
                ChargeRequest("pay:r1:CHARGE_FULL", 1, "r1"),
            ),
        )
        val b = assertIs<ChargeResult.Succeeded>(
            gateway.charge(
                ChargeRequest("pay:r2:CHARGE_FULL", 1, "r2"),
            ),
        )
        assertNotEquals(a.pgPaymentId, b.pgPaymentId)
    }

    @Test
    fun `nextBehavior DECLINE returns declined`() {
        MockPaymentGateway.nextBehavior = MockChargeBehavior.DECLINE
        val result = gateway.charge(
            ChargeRequest("pay:r1:CHARGE_FULL", 1, "r1"),
        )
        assertIs<ChargeResult.Declined>(result)
    }

    @Test
    fun `nextBehavior DELAY still succeeds after waiting`() {
        MockPaymentGateway.nextBehavior = MockChargeBehavior.DELAY
        val started = System.nanoTime()
        val result = assertIs<ChargeResult.Succeeded>(
            gateway.charge(
                ChargeRequest("pay:r1:CHARGE_FULL", 1, "r1"),
            ),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs >= 20, "expected mock delay, got ${elapsedMs}ms")
        assertTrue(result.pgEventId.startsWith("evt_"))
    }
}
