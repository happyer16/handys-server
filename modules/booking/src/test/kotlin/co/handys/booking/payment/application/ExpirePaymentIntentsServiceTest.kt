package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.support.NoOpTransactionManager
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ExpirePaymentIntentsServiceTest {
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
    private val transactions = TransactionTemplate(NoOpTransactionManager())

    private val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            paymentGateway = object : PaymentGateway {
                override fun charge(request: ChargeRequest): ChargeResult = error("no charge")

                override fun refund(request: RefundRequest): RefundResult = error("no refund")
            },
            clock = clock,
        )

    private val job =
        ExpirePaymentIntentsService(
            transactions = transactions,
            reservations = reservations,
            paymentIntents = paymentIntents,
            inventory = inventory,
            clock = clock,
        )

    @Test
    fun `expire releases hold and cancels intent`() {
        val reservationId = prepareService.execute(
            CreateDirectReservationCommand(
                propertyId = "p1",
                roomTypeOrUnitId = "deluxe",
                checkIn = LocalDate.of(2026, 10, 1),
                checkOut = LocalDate.of(2026, 10, 2),
                amountWon = 50_000L,
                mode = SellMode.HOTEL_POOL,
            ),
        ).reservationId
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId
        val expiresAt = assertNotNull(reservations.findById(reservationId)).expiresAt

        now = expiresAt.plusSeconds(1)
        job.runOnce()

        assertEquals(ReservationStatus.EXPIRED, assertNotNull(reservations.findById(reservationId)).status)
        val intent = assertNotNull(paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)))
        assertEquals(PaymentIntentStatus.Cancelled, intent.status)
        assertFalse(inventory.isConfirmed(holdId))
        // slot freed — can hold again
        inventory.hold(
            co.handys.inventory.api.HoldCommand(
                propertyId = "p1",
                roomTypeOrUnitId = "deluxe",
                checkIn = LocalDate.of(2026, 10, 1),
                checkOut = LocalDate.of(2026, 10, 2),
                mode = SellMode.HOTEL_POOL,
                expiresAt = now.plusSeconds(900),
            ),
        )
    }
}
