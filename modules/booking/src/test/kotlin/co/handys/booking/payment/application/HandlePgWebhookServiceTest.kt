package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.domain.PaymentMismatchReason
import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakeInventoryService
import co.handys.booking.payment.fake.FakeMismatchQueue
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakePgEventDedupStore
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

class HandlePgWebhookServiceTest : BehaviorSpec({
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
    val idempotency = FakeIdempotencyStore()
    val mismatches = FakeMismatchQueue()
    val pgEvents = FakePgEventDedupStore()
    val transactions = TransactionTemplate(NoOpTransactionManager())

    val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            clock = clock,
        )

    val handler =
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

    fun prepareReservation(): String =
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

    Given("a pending reservation and a success webhook") {
        val reservationId = prepareReservation()
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId

        When("the same webhook is delivered three times") {
            repeat(3) {
                handler.onSuccess(pgEventId = "evt_1", reservationId = reservationId, pgPaymentId = "pg_1")
            }

            Then("it finalizes once") {
                inventory.isConfirmed(holdId) shouldBe true
                pgEvents.recordedCount() shouldBe 1
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.CONFIRMED
                mismatches.size() shouldBe 0
            }
        }
    }

    Given("an expired reservation") {
        val reservationId = prepareReservation()
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId
        val reservation = reservations.findById(reservationId).shouldNotBeNull()
        reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))
        inventory.releaseHold(holdId)

        When("a late success webhook arrives") {
            handler.onSuccess(pgEventId = "evt_late", reservationId = reservationId, pgPaymentId = "pg_late")

            Then("it enqueues a mismatch without confirming the hold") {
                inventory.isConfirmed(holdId) shouldBe false
                mismatches.size() shouldBe 1
                val mismatch = mismatches.all().single()
                mismatch.reason shouldBe PaymentMismatchReason.LATE_SUCCESS_AFTER_EXPIRE
                mismatch.reservationId shouldBe reservationId
                mismatch.pgEventId shouldBe "evt_late"
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.EXPIRED

                val intent =
                    paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)).shouldNotBeNull()
                intent.status shouldBe PaymentIntentStatus.Succeeded
                intent.pgPaymentId shouldBe "pg_late"
            }
        }
    }
})
