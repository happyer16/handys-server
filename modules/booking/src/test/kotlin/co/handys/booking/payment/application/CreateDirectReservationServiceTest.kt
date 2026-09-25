package co.handys.booking.payment.application

import co.handys.booking.domain.PaymentSource
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.infrastructure.InMemoryPaymentIntentRepository
import co.handys.booking.payment.infrastructure.InMemoryReservationRepository
import co.handys.common.domain.SellMode
import co.handys.inventory.application.InMemoryInventoryService
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreateDirectReservationServiceTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z")
    private val reservations = InMemoryReservationRepository()
    private val paymentIntents = InMemoryPaymentIntentRepository()
    private val gateway = mock<PaymentGateway>()
    private val service =
        CreateDirectReservationService(
            inventory = InMemoryInventoryService(),
            reservations = reservations,
            paymentIntents = paymentIntents,
            paymentGateway = gateway,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `prepare creates pending direct reservation and requires-action intent without calling PG`() {
        val result = service.execute(sampleCommand())

        verify(gateway, never()).charge(any())

        val reservation = assertNotNull(reservations.findById(result.reservationId))
        assertEquals(ReservationStatus.PENDING_PAYMENT, reservation.status)
        assertEquals(PaymentSource.DIRECT, reservation.paymentSource)
        assertTrue(reservation.holdId.isNotBlank())
        assertEquals(now.plusSeconds(15 * 60), reservation.expiresAt)

        val intent = assertNotNull(paymentIntents.findById(result.paymentIntentId))
        assertEquals(PaymentIntentStatus.RequiresAction, intent.status)
        assertEquals(reservation.id, intent.reservationId)
        assertEquals(120_000L, intent.amountWon)
        assertEquals(IdempotencyKeys.chargeFull(reservation.id), intent.idempotencyKey)
        assertEquals(reservation.expiresAt, intent.expiresAt)

        assertEquals(intent.idempotencyKey, result.idempotencyKey)
        assertEquals(reservation.expiresAt, result.expiresAt)
    }

    private fun sampleCommand() =
        CreateDirectReservationCommand(
            propertyId = "property-1",
            roomTypeOrUnitId = "deluxe",
            checkIn = LocalDate.of(2026, 10, 1),
            checkOut = LocalDate.of(2026, 10, 3),
            amountWon = 120_000L,
            mode = SellMode.HOTEL_POOL,
        )
}
