package co.handys.booking.payment.application

import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakeOtaPayoutRepository
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeRefundRepository
import co.handys.booking.payment.fake.FakeReservationRepository
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
import kotlin.test.assertTrue

class OwnerSettlementServiceTest {
    private var now: Instant = Instant.parse("2026-02-15T12:00:00Z")

    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    private val inventory = co.handys.booking.payment.fake.FakeInventoryService()
    private val reservations = FakeReservationRepository()
    private val paymentIntents = FakePaymentIntentRepository()
    private val refunds = FakeRefundRepository()
    private val payouts = FakeOtaPayoutRepository()
    private val runs = FakeSettlementRunRepository()
    private val idempotency = FakeIdempotencyStore()
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
    private val ota = CreateOtaReservationService(reservations = reservations, clock = clock)
    private val postPayout =
        PostOtaPayoutService(payouts = payouts, idempotency = idempotency, clock = clock)
    private val settlement =
        RunOwnerSettlementService(
            reservations = reservations,
            paymentIntents = paymentIntents,
            refunds = refunds,
            payouts = payouts,
            runs = runs,
            idempotency = idempotency,
        )

    @Test
    fun `settlement run is idempotent`() {
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
        assertIs<ChargePaymentResult.JustSucceeded>(charge.charge(reservationId))

        val a = settlement.run("prop1", YearMonth.of(2026, 9))
        val b = settlement.run("prop1", YearMonth.of(2026, 9))

        assertEquals(a.runId, b.runId)
        assertEquals(SettlementRunStatus.SUCCEEDED, a.status)
        assertEquals(85_000L, a.ownerPayoutWon) // 100000 - floor(15000)
    }

    @Test
    fun `OTA checkout Jan payout Feb appears in February only`() {
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

        val jan = settlement.run("prop1", YearMonth.of(2026, 1))
        val feb = settlement.run("prop1", YearMonth.of(2026, 2))

        assertTrue(jan.lines.none { it.source == "OTA" })
        assertEquals(1, feb.lines.count { it.source == "OTA" })
        assertEquals(68_000L, feb.ownerPayoutWon) // 80000 - 12000
    }
}
