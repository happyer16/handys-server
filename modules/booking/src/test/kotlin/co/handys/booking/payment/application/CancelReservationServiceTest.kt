package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakeInventoryService
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeRefundRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.booking.payment.support.TransactionAssertingGateway
import co.handys.common.domain.SellMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CancelReservationServiceTest : BehaviorSpec({
    isolationMode = io.kotest.core.spec.IsolationMode.InstancePerTest

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
    val refunds = FakeRefundRepository()
    val idempotency = FakeIdempotencyStore()
    val transactions = TransactionTemplate(NoOpTransactionManager())

    var gatewayBehaviour: (ChargeRequest) -> ChargeResult = {
        ChargeResult.Succeeded(pgPaymentId = "pg_1", pgEventId = "evt_1")
    }
    val gateway = TransactionAssertingGateway(behaviour = { gatewayBehaviour(it) })

    val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            clock = clock,
        )

    val chargeService =
        ChargePaymentService(
            transactions = transactions,
            gateway = gateway,
            reservations = reservations,
            paymentIntents = paymentIntents,
            idempotency = idempotency,
            inventory = inventory,
            clock = clock,
        )

    val cancelService =
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

    fun prepareAndPay(checkIn: LocalDate): String {
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
        chargeService.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()
        return reservationId
    }

    Given("a paid reservation 25h before check-in") {
        // checkIn 2026-09-27 00:00 UTC → windowEnd 2026-09-26 00:00; now is 25th 12:00 → refund
        val reservationId = prepareAndPay(checkIn = LocalDate.of(2026, 9, 27))

        When("cancel is called") {
            val result = cancelService.cancel(reservationId)

            Then("it refunds the full amount") {
                result.refundAmountWon shouldBe 120_000L
                gateway.refundCount shouldBe 1
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.CANCELLED
                refunds.findByReservationId(reservationId).shouldNotBeNull().amountWon shouldBe 120_000L
            }
        }
    }

    Given("a paid reservation 12h before check-in") {
        // checkIn 2026-09-26 00:00 → windowEnd 2026-09-25 00:00; now 25th 12:00 → no refund
        val reservationId = prepareAndPay(checkIn = LocalDate.of(2026, 9, 26))

        When("cancel is called") {
            val result = cancelService.cancel(reservationId)

            Then("it refunds zero") {
                result.refundAmountWon shouldBe 0L
                gateway.refundCount shouldBe 0
                refunds.findByReservationId(reservationId).shouldBeNull()
            }
        }
    }

    Given("a paid reservation exactly at the 24h window end") {
        val reservationId = prepareAndPay(checkIn = LocalDate.of(2026, 9, 27))
        now = Instant.parse("2026-09-26T00:00:00Z")

        When("cancel is called") {
            val result = cancelService.cancel(reservationId)

            Then("it refunds zero") {
                result.refundAmountWon shouldBe 0L
                gateway.refundCount shouldBe 0
            }
        }
    }
})
