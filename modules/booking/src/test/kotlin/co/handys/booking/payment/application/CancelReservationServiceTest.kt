package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeRefundRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.booking.payment.support.TransactionAssertingGateway
import co.handys.common.domain.SellMode
import co.handys.booking.payment.fake.FakeInventoryService
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CancelReservationServiceTest {
    private var now: Instant = Instant.parse("2026-09-25T12:00:00Z")

    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    private val inventory = co.handys.booking.payment.fake.FakeInventoryService()
    private val reservations = FakeReservationRepository()
    private val paymentIntents = FakePaymentIntentRepository()
    private val refunds = FakeRefundRepository()
    private val idempotency = FakeIdempotencyStore()
    private val transactions = TransactionTemplate(NoOpTransactionManager())

    private var gatewayBehaviour: (ChargeRequest) -> ChargeResult = {
        ChargeResult.Succeeded(pgPaymentId = "pg_1", pgEventId = "evt_1")
    }
    private val gateway = TransactionAssertingGateway(behaviour = { gatewayBehaviour(it) })

    private val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            paymentGateway = gateway,
            clock = clock,
        )

    private val chargeService =
        ChargePaymentService(
            transactions = transactions,
            gateway = gateway,
            reservations = reservations,
            paymentIntents = paymentIntents,
            idempotency = idempotency,
            inventory = inventory,
            clock = clock,
        )

    private val cancelService =
        CancelReservationService(
            transactions = transactions,
            gateway = gateway,
            reservations = reservations,
            paymentIntents = paymentIntents,
            refunds = refunds,
            idempotency = idempotency,
            inventory = inventory,
            clock = clock,
        )

    @Test
    fun `cancel 25h before checkin refunds full`() {
        // checkIn 2026-09-27 00:00 UTC → windowEnd 2026-09-26 00:00; now is 25th 12:00 → refund
        val reservationId = prepareAndPay(checkIn = LocalDate.of(2026, 9, 27))

        val result = cancelService.cancel(reservationId)

        assertEquals(120_000L, result.refundAmountWon)
        assertEquals(1, gateway.refundCount)
        assertEquals(ReservationStatus.CANCELLED, assertNotNull(reservations.findById(reservationId)).status)
        assertEquals(120_000L, assertNotNull(refunds.findByReservationId(reservationId)).amountWon)
    }

    @Test
    fun `cancel 12h before checkin refunds zero`() {
        // checkIn 2026-09-26 00:00 → windowEnd 2026-09-25 00:00; now 25th 12:00 → no refund
        val reservationId = prepareAndPay(checkIn = LocalDate.of(2026, 9, 26))

        val result = cancelService.cancel(reservationId)

        assertEquals(0L, result.refundAmountWon)
        assertEquals(0, gateway.refundCount)
        assertNull(refunds.findByReservationId(reservationId))
    }

    @Test
    fun `cancel exactly 24h before checkin refunds zero`() {
        // checkIn 2026-09-26 12:00 equiv: use checkIn date 2026-09-26 → windowEnd 09-25 00:00
        // Set now exactly to windowEnd for checkIn 2026-09-27 → windowEnd = 2026-09-26T00:00Z
        val reservationId = prepareAndPay(checkIn = LocalDate.of(2026, 9, 27))
        now = Instant.parse("2026-09-26T00:00:00Z")

        val result = cancelService.cancel(reservationId)

        assertEquals(0L, result.refundAmountWon)
        assertEquals(0, gateway.refundCount)
    }

    private fun prepareAndPay(checkIn: LocalDate): String {
        val reservationId =
            prepareService
                .execute(
                    CreateDirectReservationCommand(
                        propertyId = "p1",
                        roomTypeOrUnitId = "deluxe",
                        checkIn = checkIn,
                        checkOut = checkIn.plusDays(1),
                        amountWon = 120_000L,
                        mode = SellMode.HOTEL_POOL,
                    ),
                ).reservationId
        assertIs<ChargePaymentResult.JustSucceeded>(chargeService.charge(reservationId))
        return reservationId
    }
}
