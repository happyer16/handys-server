package co.handys.booking.payment.application

import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakeInventoryService
import co.handys.booking.payment.fake.FakeOtaPayoutRepository
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeRefundRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.fake.FakeSettlementBatchJobLock
import co.handys.booking.payment.fake.FakeSettlementRunRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.booking.payment.support.TransactionAssertingGateway
import co.handys.common.domain.SellMode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class SettlementBatchJobLockTest : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerTest

    val lock = FakeSettlementBatchJobLock()
    val now = Instant.parse("2026-10-01T02:00:00Z")
    val day = LocalDate.of(2026, 10, 1)

    Given("a job lock for a date") {
        When("tryAcquire is called twice for the same jobDate") {
            val first = lock.tryAcquire(day, now)

            Then("the second acquire returns null") {
                first.shouldNotBeNull()
                lock.tryAcquire(day, now.plusSeconds(1)).shouldBeNull()
                lock.findByJobDate(day)?.id shouldBe first.id
            }
        }
    }

    Given("two different jobDates") {
        When("tryAcquire is called for each") {
            Then("both can acquire") {
                lock.tryAcquire(day, now).shouldNotBeNull()
                lock.tryAcquire(day.plusDays(1), now).shouldNotBeNull()
            }
        }
    }
})

class OwnerSettlementBatchServiceTest : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerTest

    var now: Instant = Instant.parse("2026-10-01T02:00:00Z")

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
    val lock = FakeSettlementBatchJobLock()
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
    val settlement =
        RunOwnerSettlementService(
            reservations = reservations,
            paymentIntents = paymentIntents,
            refunds = refunds,
            payouts = payouts,
            runs = runs,
            idempotency = idempotency,
        )
    val batch =
        OwnerSettlementBatchService(
            lock = lock,
            settlements = settlement,
            reservations = reservations,
            clock = clock,
        )

    Given("a charged reservation in the previous month") {
        now = Instant.parse("2026-09-15T12:00:00Z")
        val created =
            prepare.execute(
                CreateDirectReservationCommand(
                    propertyId = "prop-1",
                    roomTypeOrUnitId = "deluxe",
                    checkIn = LocalDate.of(2026, 9, 10),
                    checkOut = LocalDate.of(2026, 9, 12),
                    amountWon = 100_000L,
                    mode = SellMode.HOTEL_POOL,
                ),
            )
        charge.charge(created.reservationId)
        now = Instant.parse("2026-10-01T02:00:00Z")

        When("the batch runs for the first time") {
            val result = batch.runForDate(LocalDate.of(2026, 10, 1))

            Then("it acquires the lock and settles the previous month") {
                val completed = result.shouldBeInstanceOf<OwnerSettlementBatchResult.Completed>()
                completed.period shouldBe YearMonth.of(2026, 9)
                completed.runs.size shouldBe 1
                lock.findByJobDate(LocalDate.of(2026, 10, 1))?.status shouldBe SettlementBatchJobStatus.SUCCEEDED
            }
        }
    }

    Given("a batch that already completed for the jobDate") {
        now = Instant.parse("2026-09-15T12:00:00Z")
        val created =
            prepare.execute(
                CreateDirectReservationCommand(
                    propertyId = "prop-1",
                    roomTypeOrUnitId = "deluxe",
                    checkIn = LocalDate.of(2026, 9, 10),
                    checkOut = LocalDate.of(2026, 9, 12),
                    amountWon = 100_000L,
                    mode = SellMode.HOTEL_POOL,
                ),
            )
        charge.charge(created.reservationId)
        now = Instant.parse("2026-10-01T02:00:00Z")
        val jobDate = LocalDate.of(2026, 10, 1)
        batch.runForDate(jobDate).shouldBeInstanceOf<OwnerSettlementBatchResult.Completed>()
        val runCountAfterFirst = runs.findByKey("stl:prop-1:2026-09:ps-v1")

        When("the batch runs again for the same jobDate") {
            val second = batch.runForDate(jobDate)

            Then("it is skipped without a new settlement") {
                second.shouldBeInstanceOf<OwnerSettlementBatchResult.Skipped>()
                runs.findByKey("stl:prop-1:2026-09:ps-v1")?.runId shouldBe runCountAfterFirst?.runId
            }
        }
    }
})
