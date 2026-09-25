package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.infrastructure.InMemoryIdempotencyStore
import co.handys.booking.payment.infrastructure.InMemoryPaymentIntentRepository
import co.handys.booking.payment.infrastructure.InMemoryReservationRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.booking.payment.support.TransactionAssertingGateway
import co.handys.common.domain.SellMode
import co.handys.inventory.application.InMemoryInventoryService
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChargePaymentServiceTest {
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
    private val transactions = TransactionTemplate(NoOpTransactionManager())

    private var gatewayBehaviour: (ChargeRequest) -> ChargeResult = { succeeded() }
    private val gateway = TransactionAssertingGateway { gatewayBehaviour(it) }

    private val service =
        ChargePaymentService(
            transactions = transactions,
            gateway = gateway,
            reservations = reservations,
            paymentIntents = paymentIntents,
            idempotency = idempotency,
            inventory = inventory,
            clock = clock,
        )

    private val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            paymentGateway = gateway,
            clock = clock,
        )

    @Test
    fun `transaction probe is live - the template really activates a transaction`() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        val insideTransaction = transactions.execute { TransactionSynchronizationManager.isActualTransactionActive() }
        assertEquals(true, insideTransaction)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    @Test
    fun `five charge calls invoke gateway only once`() {
        val reservationId = prepareReservation()

        val results = (1..5).map { service.charge(reservationId) }

        assertEquals(1, gateway.chargeCount)
        val first = assertIs<ChargePaymentResult.JustSucceeded>(results.first())
        results.drop(1).forEach {
            val replay = assertIs<ChargePaymentResult.AlreadySucceeded>(it)
            assertEquals(first.pgPaymentId, replay.pgPaymentId)
        }
    }

    @Test
    fun `succeeded charge confirms inventory and reservation in one finalize step`() {
        val reservationId = prepareReservation()
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId

        val result = assertIs<ChargePaymentResult.JustSucceeded>(service.charge(reservationId))

        assertTrue(inventory.isConfirmed(holdId))
        assertEquals(ReservationStatus.CONFIRMED, assertNotNull(reservations.findById(reservationId)).status)

        val intent = assertNotNull(paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)))
        assertEquals(PaymentIntentStatus.Succeeded, intent.status)
        assertEquals(result.pgPaymentId, intent.pgPaymentId)

        val record = assertIs<BeginResult.Existing>(idempotency.begin(IdempotencyKeys.chargeFull(reservationId)))
        assertTrue(record.entry.terminal)
        assertTrue(assertNotNull(record.entry.payload).contains(result.pgPaymentId))
    }

    @Test
    fun `already succeeded intent replays the stored response without calling the gateway`() {
        val reservationId = prepareReservation()
        val first = assertIs<ChargePaymentResult.JustSucceeded>(service.charge(reservationId))

        val replay = assertIs<ChargePaymentResult.AlreadySucceeded>(service.charge(reservationId))

        assertEquals(first.pgPaymentId, replay.pgPaymentId)
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun `in-flight idempotency key reports progress instead of charging again`() {
        val reservationId = prepareReservation()
        val key = IdempotencyKeys.chargeFull(reservationId)
        assertIs<BeginResult.Acquired>(idempotency.begin(key))

        val result = assertIs<ChargePaymentResult.InProgress>(service.charge(reservationId))

        assertEquals(key, result.idempotencyKey)
        assertEquals(0, gateway.chargeCount)
    }

    @Test
    fun `re-entry while the gateway call is in flight does not charge twice`() {
        val reservationId = prepareReservation()
        var reentrant: ChargePaymentResult? = null
        gatewayBehaviour = {
            reentrant = service.charge(reservationId)
            succeeded()
        }

        val result = assertIs<ChargePaymentResult.JustSucceeded>(service.charge(reservationId))

        assertEquals(1, gateway.chargeCount)
        assertIs<ChargePaymentResult.InProgress>(reentrant)
        assertEquals(ReservationStatus.CONFIRMED, assertNotNull(reservations.findById(reservationId)).status)
        assertTrue(result.pgPaymentId.isNotBlank())
    }

    @Test
    fun `declined charge leaves the reservation pending and the hold unconfirmed`() {
        val reservationId = prepareReservation()
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId
        gatewayBehaviour = { ChargeResult.Declined("insufficient_funds") }

        val result = assertIs<ChargePaymentResult.Declined>(service.charge(reservationId))

        assertEquals("insufficient_funds", result.reason)
        assertFalse(inventory.isConfirmed(holdId))
        assertEquals(ReservationStatus.PENDING_PAYMENT, assertNotNull(reservations.findById(reservationId)).status)

        val intent = assertNotNull(paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)))
        assertEquals(PaymentIntentStatus.RequiresAction, intent.status)

        // A decline moved no money, so the key must be re-acquirable rather than frozen as terminal.
        assertIs<BeginResult.Acquired>(idempotency.begin(IdempotencyKeys.chargeFull(reservationId)))
    }

    @Test
    fun `retry after decline reaches the gateway again and can succeed`() {
        val reservationId = prepareReservation()
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId
        gatewayBehaviour = { ChargeResult.Declined("insufficient_funds") }
        assertIs<ChargePaymentResult.Declined>(service.charge(reservationId))

        gatewayBehaviour = { succeeded() }
        val retry = assertIs<ChargePaymentResult.JustSucceeded>(service.charge(reservationId))

        assertEquals(2, gateway.chargeCount)
        assertEquals("pg_test_1", retry.pgPaymentId)
        assertTrue(inventory.isConfirmed(holdId))
        assertEquals(ReservationStatus.CONFIRMED, assertNotNull(reservations.findById(reservationId)).status)
    }

    @Test
    fun `a declined retry still holds off a concurrent caller`() {
        val reservationId = prepareReservation()
        gatewayBehaviour = { ChargeResult.Declined("insufficient_funds") }
        assertIs<ChargePaymentResult.Declined>(service.charge(reservationId))

        var reentrant: ChargePaymentResult? = null
        gatewayBehaviour = {
            reentrant = service.charge(reservationId)
            succeeded()
        }
        assertIs<ChargePaymentResult.JustSucceeded>(service.charge(reservationId))

        assertIs<ChargePaymentResult.InProgress>(reentrant)
        assertEquals(2, gateway.chargeCount)
    }

    @Test
    fun `expired reservation is rejected before the gateway`() {
        val reservationId = prepareReservation()
        val reservation = assertNotNull(reservations.findById(reservationId))
        reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))

        val result = assertIs<ChargePaymentResult.Expired>(service.charge(reservationId))

        assertEquals(ChargePaymentResult.INTENT_EXPIRED, result.code)
        assertEquals(0, gateway.chargeCount)
    }

    @Test
    fun `charge after the intent ttl is rejected before the gateway`() {
        val reservationId = prepareReservation()
        now = now.plusSeconds(16 * 60)

        assertIs<ChargePaymentResult.Expired>(service.charge(reservationId))

        assertEquals(0, gateway.chargeCount)
    }

    @Test
    fun `ttl elapsing during the gateway call does not silently confirm`() {
        val reservationId = prepareReservation()
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId
        gatewayBehaviour = {
            now = now.plusSeconds(16 * 60)
            succeeded()
        }

        val result = assertIs<ChargePaymentResult.LateSuccessMismatch>(service.charge(reservationId))

        assertEquals(ChargePaymentResult.LATE_SUCCESS_AFTER_EXPIRE, result.code)
        assertEquals("pg_test_1", result.pgPaymentId)
        assertFalse(inventory.isConfirmed(holdId))
        assertEquals(ReservationStatus.PENDING_PAYMENT, assertNotNull(reservations.findById(reservationId)).status)

        // The money moved, so the intent keeps the gateway id and the record is terminal: never charge again.
        val intent = assertNotNull(paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)))
        assertEquals(PaymentIntentStatus.Succeeded, intent.status)
        assertEquals("pg_test_1", intent.pgPaymentId)
        val record = assertIs<BeginResult.Existing>(idempotency.begin(IdempotencyKeys.chargeFull(reservationId)))
        assertTrue(record.entry.terminal)
    }

    @Test
    fun `a reservation expired during the gateway call does not confirm the hold`() {
        val reservationId = prepareReservation()
        val holdId = assertNotNull(reservations.findById(reservationId)).holdId
        gatewayBehaviour = {
            val reservation = assertNotNull(reservations.findById(reservationId))
            reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))
            inventory.releaseHold(holdId)
            succeeded()
        }

        assertIs<ChargePaymentResult.LateSuccessMismatch>(service.charge(reservationId))

        assertFalse(inventory.isConfirmed(holdId))
        assertEquals(ReservationStatus.EXPIRED, assertNotNull(reservations.findById(reservationId)).status)
    }

    @Test
    fun `a mismatched charge replays as a mismatch not as a success`() {
        val reservationId = prepareReservation()
        gatewayBehaviour = {
            val reservation = assertNotNull(reservations.findById(reservationId))
            reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))
            succeeded()
        }
        assertIs<ChargePaymentResult.LateSuccessMismatch>(service.charge(reservationId))

        val replay = assertIs<ChargePaymentResult.LateSuccessMismatch>(service.charge(reservationId))

        assertEquals("pg_test_1", replay.pgPaymentId)
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun `charge refuses to run inside an outer transaction`() {
        val reservationId = prepareReservation()

        val failure =
            assertFailsWith<IllegalStateException> {
                transactions.execute { service.charge(reservationId) }
            }

        assertTrue(assertNotNull(failure.message).contains("must not run inside an outer transaction"))
        assertEquals(0, gateway.chargeCount)
    }

    private fun prepareReservation(): String =
        prepareService.execute(
            CreateDirectReservationCommand(
                propertyId = "property-1",
                roomTypeOrUnitId = "deluxe",
                checkIn = LocalDate.of(2026, 10, 1),
                checkOut = LocalDate.of(2026, 10, 3),
                amountWon = 120_000L,
                mode = SellMode.HOTEL_POOL,
            ),
        ).reservationId

    private fun succeeded() =
        ChargeResult.Succeeded(pgPaymentId = "pg_test_1", pgEventId = "evt_test_1")
}
