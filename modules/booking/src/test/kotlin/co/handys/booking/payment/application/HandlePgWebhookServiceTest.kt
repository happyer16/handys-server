package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.domain.PaymentMismatchReason
import co.handys.booking.payment.infrastructure.InMemoryIdempotencyStore
import co.handys.booking.payment.infrastructure.InMemoryMismatchQueue
import co.handys.booking.payment.infrastructure.InMemoryPaymentIntentRepository
import co.handys.booking.payment.infrastructure.InMemoryPgEventDedupStore
import co.handys.booking.payment.infrastructure.InMemoryReservationRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.common.domain.SellMode
import co.handys.inventory.application.InMemoryInventoryService
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
import kotlin.test.assertTrue

class HandlePgWebhookServiceTest {
    private var now: Instant = Instant.parse("2026-09-25T12:00:00Z")

    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    private val inventory = InMemoryInventoryService()
    private val reservations = InMemoryReservationRepository()
    private val paymentIntents = InMemoryPaymentIntentRepository()
    private val idempotency = InMemoryIdempotencyStore()
    private val mismatches = InMemoryMismatchQueue()
    private val pgEvents = InMemoryPgEventDedupStore()
    private val transactions = TransactionTemplate(NoOpTransactionManager())

    private val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            paymentGateway = object : PaymentGateway {
                override fun charge(request: ChargeRequest): ChargeResult =
                    error("prepare must not charge")

                override fun refund(request: RefundRequest): RefundResult =
                    error("prepare must not refund")
            },
            clock = clock,
        )

    private val handler =
        HandlePgWebhookService(
            transactions = transactions,
            reservations = reservations,
            paymentIntents = paymentIntents,
            idempotency = idempotency,
            inventory = inventory,
            mismatches = mismatches,
            pgEvents = pgEvents,
            clock = clock,
        )

    @Test
    fun `duplicate webhook finalizes once`() {
        val reservationId = prepareReservation()
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId

        repeat(3) {
            handler.onSuccess(pgEventId = "evt_1", reservationId = reservationId, pgPaymentId = "pg_1")
        }

        assertTrue(inventory.isConfirmed(holdId))
        assertEquals(1, pgEvents.recordedCount())
        assertEquals(ReservationStatus.CONFIRMED, assertNotNull(reservations.findById(reservationId)).status)
        assertEquals(0, mismatches.size())
    }

    @Test
    fun `late success after expire enqueues mismatch`() {
        val reservationId = prepareReservation()
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId
        val reservation = assertNotNull(reservations.findById(reservationId))
        reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))
        inventory.releaseHold(holdId)

        handler.onSuccess(pgEventId = "evt_late", reservationId = reservationId, pgPaymentId = "pg_late")

        assertFalse(inventory.isConfirmed(holdId))
        assertEquals(1, mismatches.size())
        val mismatch = mismatches.all().single()
        assertEquals(PaymentMismatchReason.LATE_SUCCESS_AFTER_EXPIRE, mismatch.reason)
        assertEquals(reservationId, mismatch.reservationId)
        assertEquals("evt_late", mismatch.pgEventId)
        assertEquals(ReservationStatus.EXPIRED, assertNotNull(reservations.findById(reservationId)).status)

        val intent = assertNotNull(paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)))
        assertEquals(PaymentIntentStatus.Succeeded, intent.status)
        assertEquals("pg_late", intent.pgPaymentId)
    }

    private fun prepareReservation(): String =
        prepareService
            .execute(
                CreateDirectReservationCommand(
                    propertyId = "property-1",
                    roomTypeOrUnitId = "deluxe",
                    checkIn = LocalDate.of(2026, 10, 1),
                    checkOut = LocalDate.of(2026, 10, 3),
                    amountWon = 120_000L,
                    mode = SellMode.HOTEL_POOL,
                ),
            ).reservationId
}
