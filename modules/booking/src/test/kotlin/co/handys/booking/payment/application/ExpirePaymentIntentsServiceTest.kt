package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.fake.FakeInventoryService
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.common.domain.SellMode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ExpirePaymentIntentsServiceTest : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerTest

    var now: Instant = Instant.parse("2026-09-25T12:00:00Z")

    val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    val inventory = FakeInventoryService()
    val reservations = FakeReservationRepository()
    val paymentIntents = FakePaymentIntentRepository()
    val transactions = TransactionTemplate(NoOpTransactionManager())

    val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            clock = clock,
        )

    val job =
        ExpirePaymentIntentsService(
            transactions = transactions,
            reservations = reservations,
            paymentIntents = paymentIntents,
            inventory = inventory,
            clock = clock,
        )

    Given("a pending reservation past its expiresAt") {
        val reservationId =
            prepareService
                .execute(
                    CreateDirectReservationCommand(
                        propertyId = "p1",
                        roomTypeOrUnitId = "deluxe",
                        checkIn = LocalDate.of(2026, 10, 1),
                        checkOut = LocalDate.of(2026, 10, 2),
                        amountWon = 50_000L,
                        mode = SellMode.HOTEL_POOL,
                    ),
                ).reservationId
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId
        val expiresAt = reservations.findById(reservationId).shouldNotBeNull().expiresAt
        now = expiresAt.plusSeconds(1)

        When("the expire job runs") {
            job.runOnce()

            Then("it releases the hold and cancels the intent") {
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.EXPIRED
                val intent =
                    paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)).shouldNotBeNull()
                intent.status shouldBe PaymentIntentStatus.Cancelled
                inventory.isConfirmed(holdId) shouldBe false
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
    }
})
