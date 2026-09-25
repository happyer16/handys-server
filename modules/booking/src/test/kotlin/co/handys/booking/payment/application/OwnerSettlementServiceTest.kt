package co.handys.booking.payment.application

import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakeInventoryService
import co.handys.booking.payment.fake.FakeOtaPayoutRepository
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeRefundRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.fake.FakeSettlementRunRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.booking.payment.support.TransactionAssertingGateway
import co.handys.common.domain.SellMode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class OwnerSettlementServiceTest : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerTest

    var now: Instant = Instant.parse("2026-02-15T12:00:00Z")

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
    val payouts = FakeOtaPayoutRepository()
    val runs = FakeSettlementRunRepository()
    val idempotency = FakeIdempotencyStore()
    val transactions = TransactionTemplate(NoOpTransactionManager())
    val gateway =
        TransactionAssertingGateway(behaviour = {
            ChargeResult.Succeeded(pgPaymentId = "pg_1", pgEventId = "evt_1")
        })

    val prepare =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            clock = clock,
        )
    val charge =
        ChargePaymentService(
            transactions = transactions,
            gateway = gateway,
            reservations = reservations,
            paymentIntents = paymentIntents,
            idempotency = idempotency,
            inventory = inventory,
            clock = clock,
        )
    val ota = CreateOtaReservationService(reservations = reservations, clock = clock)
    val postPayout =
        PostOtaPayoutService(payouts = payouts, idempotency = idempotency, clock = clock)
    val settlement =
        RunOwnerSettlementService(
            reservations = reservations,
            paymentIntents = paymentIntents,
            refunds = refunds,
            payouts = payouts,
            runs = runs,
            idempotency = idempotency,
        )

    Given("a charged direct reservation in September") {
        now = Instant.parse("2026-09-20T12:00:00Z")
        val reservationId =
            prepare
                .execute(
                    CreateDirectReservationCommand(
                        propertyId = "prop1",
                        roomTypeOrUnitId = "deluxe",
                        checkIn = LocalDate.of(2026, 9, 10),
                        checkOut = LocalDate.of(2026, 9, 12),
                        amountWon = 100_000L,
                        mode = SellMode.HOTEL_POOL,
                    ),
                ).reservationId
        charge.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()

        When("settlement is run twice for the same month") {
            val a = settlement.run("prop1", YearMonth.of(2026, 9))
            val b = settlement.run("prop1", YearMonth.of(2026, 9))

            Then("the run is idempotent") {
                a.runId shouldBe b.runId
                a.status shouldBe SettlementRunStatus.SUCCEEDED
                a.ownerPayoutWon shouldBe 85_000L // 100000 - floor(15000)
            }
        }
    }

    Given("an OTA checkout in January with payout in February") {
        now = Instant.parse("2026-01-15T12:00:00Z")
        val otaReservation =
            ota.execute(
                CreateOtaReservationCommand(
                    propertyId = "prop1",
                    roomTypeOrUnitId = "deluxe",
                    checkIn = LocalDate.of(2026, 1, 10),
                    checkOut = LocalDate.of(2026, 1, 12),
                    amountWon = 80_000L,
                    mode = SellMode.HOTEL_POOL,
                ),
            )
        now = Instant.parse("2026-02-05T12:00:00Z")
        postPayout.post(
            PostOtaPayoutCommand(
                channel = "ota_a",
                channelPayoutId = "po_1",
                propertyId = "prop1",
                amountWon = 80_000L,
                reservationIds = listOf(otaReservation.id),
            ),
        )

        When("settlement runs for January and February") {
            val jan = settlement.run("prop1", YearMonth.of(2026, 1))
            val feb = settlement.run("prop1", YearMonth.of(2026, 2))

            Then("the payout appears in February only") {
                jan.lines.none { it.source == "OTA" } shouldBe true
                feb.lines.count { it.source == "OTA" } shouldBe 1
                feb.ownerPayoutWon shouldBe 68_000L // 80000 - 12000
            }
        }
    }
})
