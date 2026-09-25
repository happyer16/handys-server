package co.handys.booking.payment.application

import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakeOtaPayoutRepository
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeRefundRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.fake.FakeSettlementBatchJobLock
import co.handys.booking.payment.fake.FakeSettlementRunRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.booking.payment.support.TransactionAssertingGateway
import co.handys.common.domain.SellMode
import co.handys.booking.payment.fake.FakeInventoryService
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettlementBatchJobLockTest {
    private val lock = FakeSettlementBatchJobLock()
    private val now = Instant.parse("2026-10-01T02:00:00Z")
    private val day = LocalDate.of(2026, 10, 1)

    @Test
    fun `second tryAcquire for same jobDate returns null`() {
        val first = lock.tryAcquire(day, now)
        assertNotNull(first)
        assertNull(lock.tryAcquire(day, now.plusSeconds(1)))
        assertEquals(first.id, lock.findByJobDate(day)?.id)
    }

    @Test
    fun `different jobDates can both acquire`() {
        assertNotNull(lock.tryAcquire(day, now))
        assertNotNull(lock.tryAcquire(day.plusDays(1), now))
    }
}

class OwnerSettlementBatchServiceTest {
    private var now: Instant = Instant.parse("2026-10-01T02:00:00Z")

    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    private val inventory = FakeInventoryService()
    private val reservations = FakeReservationRepository()
    private val paymentIntents = FakePaymentIntentRepository()
    private val refunds = FakeRefundRepository()
    private val payouts = FakeOtaPayoutRepository()
    private val runs = FakeSettlementRunRepository()
    private val idempotency = FakeIdempotencyStore()
    private val lock = FakeSettlementBatchJobLock()
    private val transactions = TransactionTemplate(NoOpTransactionManager())
    private val gateway =
        TransactionAssertingGateway(behaviour = {
            ChargeResult.Succeeded(pgPaymentId = "pg_1", pgEventId = "evt_1")
        })

    private val prepare =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            paymentGateway = gateway,
            clock = clock,
        )
    private val charge =
        ChargePaymentService(
            transactions = transactions,
            gateway = gateway,
            reservations = reservations,
            paymentIntents = paymentIntents,
            idempotency = idempotency,
            inventory = inventory,
            clock = clock,
        )
    private val settlement =
        RunOwnerSettlementService(
            reservations = reservations,
            paymentIntents = paymentIntents,
            refunds = refunds,
            payouts = payouts,
            runs = runs,
            idempotency = idempotency,
        )
    private val batch =
        OwnerSettlementBatchService(
            lock = lock,
            settlements = settlement,
            reservations = reservations,
            clock = clock,
        )

    @Test
    fun `first run acquires lock and settles previous month`() {
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
        val result = batch.runForDate(LocalDate.of(2026, 10, 1))
        val completed = assertIs<OwnerSettlementBatchResult.Completed>(result)
        assertEquals(YearMonth.of(2026, 9), completed.period)
        assertEquals(1, completed.runs.size)
        assertEquals(SettlementBatchJobStatus.SUCCEEDED, lock.findByJobDate(LocalDate.of(2026, 10, 1))?.status)
    }

    @Test
    fun `second run same jobDate is skipped without new settlement`() {
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
        assertIs<OwnerSettlementBatchResult.Completed>(batch.runForDate(jobDate))
        val runCountAfterFirst = runs.findByKey("stl:prop-1:2026-09:ps-v1")

        val second = batch.runForDate(jobDate)
        assertIs<OwnerSettlementBatchResult.Skipped>(second)
        assertEquals(runCountAfterFirst?.runId, runs.findByKey("stl:prop-1:2026-09:ps-v1")?.runId)
    }
}
