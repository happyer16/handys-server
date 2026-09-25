package co.handys.booking.payment.application

import co.handys.booking.domain.PaymentSource
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.fake.FakeInventoryService
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.common.domain.SellMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CreateDirectReservationServiceTest : BehaviorSpec({
    val now = Instant.parse("2026-09-25T12:00:00Z")
    val reservations = FakeReservationRepository()
    val paymentIntents = FakePaymentIntentRepository()
    val gateway = mock<PaymentGateway>()
    val service =
        CreateDirectReservationService(
            inventory = FakeInventoryService(),
            reservations = reservations,
            paymentIntents = paymentIntents,
            paymentGateway = gateway,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    Given("a direct reservation prepare command") {
        When("execute is called") {
            val result = service.execute(sampleCommand())

            Then("it creates a pending reservation and requires-action intent without calling PG") {
                verify(gateway, never()).charge(any())

                val reservation = reservations.findById(result.reservationId).shouldNotBeNull()
                reservation.status shouldBe ReservationStatus.PENDING_PAYMENT
                reservation.paymentSource shouldBe PaymentSource.DIRECT
                reservation.holdId.shouldNotBeBlank()
                reservation.expiresAt shouldBe now.plusSeconds(15 * 60)

                val intent = paymentIntents.findById(result.paymentIntentId).shouldNotBeNull()
                intent.status shouldBe PaymentIntentStatus.RequiresAction
                intent.reservationId shouldBe reservation.id
                intent.amountWon shouldBe 120_000L
                intent.idempotencyKey shouldBe IdempotencyKeys.chargeFull(reservation.id)
                intent.expiresAt shouldBe reservation.expiresAt

                result.idempotencyKey shouldBe intent.idempotencyKey
                result.expiresAt shouldBe reservation.expiresAt
            }
        }
    }
})

private fun sampleCommand() =
    CreateDirectReservationCommand(
        propertyId = "property-1",
        roomTypeOrUnitId = "deluxe",
        checkIn = LocalDate.of(2026, 10, 1),
        checkOut = LocalDate.of(2026, 10, 3),
        amountWon = 120_000L,
        mode = SellMode.HOTEL_POOL,
    )
