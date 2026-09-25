package co.handys.booking.payment.application

import co.handys.booking.domain.PaymentSource
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import java.time.YearMonth
import java.util.UUID
import kotlin.math.floor

class RunOwnerSettlementService(
    private val reservations: ReservationRepository,
    private val paymentIntents: PaymentIntentRepository,
    private val refunds: RefundRepository,
    private val payouts: OtaPayoutRepository,
    private val runs: SettlementRunRepository,
    private val idempotency: IdempotencyStore,
) {
    fun run(propertyId: String, period: YearMonth): SettlementRun {
        val key = IdempotencyKeys.settlement(propertyId, period)
        when (val begun = idempotency.begin(key)) {
            is BeginResult.Existing -> {
                if (begun.entry.terminal) {
                    return runs.findByKey(key) ?: error("terminal settlement missing for $key")
                }
            }
            BeginResult.Acquired -> Unit
        }
        runs.findByKey(key)?.let {
            idempotency.complete(key, it.runId, terminal = true)
            return it
        }

        val lines = mutableListOf<SettlementLine>()
        val seen = mutableSetOf<Pair<String, String>>()

        // DIRECT: checkout in month, charge succeeded, net > 0
        for (reservation in reservations.findByStatus(ReservationStatus.CONFIRMED) +
            reservations.findByStatus(ReservationStatus.CANCELLED)) {
            if (reservation.propertyId != propertyId) continue
            if (reservation.paymentSource != PaymentSource.DIRECT) continue
            if (YearMonth.from(reservation.checkOut) != period) continue
            val chargeKey = IdempotencyKeys.chargeFull(reservation.id)
            val intent = paymentIntents.findByIdempotencyKey(chargeKey) ?: continue
            if (intent.status != PaymentIntentStatus.Succeeded) continue
            val refundAmount = refunds.findByReservationId(reservation.id)?.amountWon ?: 0L
            val net = reservation.amountWon - refundAmount
            if (net <= 0L) continue
            val identity = reservation.id to intent.id
            if (!seen.add(identity)) {
                return fail(key, propertyId, period, "duplicate line for $identity")
            }
            lines += line(reservation.id, intent.id, net, "DIRECT")
        }

        // OTA: posted in month
        for (payout in payouts.findPostedInPropertyMonth(propertyId, period.year, period.monthValue)) {
            val share =
                if (payout.reservationIds.isEmpty()) {
                    payout.amountWon
                } else {
                    payout.amountWon / payout.reservationIds.size
                }
            for (reservationId in payout.reservationIds.ifEmpty { listOf("payout-${payout.id}") }) {
                val identity = reservationId to "ota:${payout.id}"
                if (!seen.add(identity)) {
                    return fail(key, propertyId, period, "duplicate line for $identity")
                }
                lines += line(reservationId, "ota:${payout.id}", share, "OTA")
            }
        }

        val ownerPayout = lines.sumOf { it.ownerPayoutWon }
        val run =
            SettlementRun(
                runId = UUID.randomUUID().toString(),
                propertyId = propertyId,
                period = period,
                status = SettlementRunStatus.SUCCEEDED,
                ownerPayoutWon = ownerPayout,
                lines = lines,
                errorCode = null,
                idempotencyKey = key,
            )
        runs.save(run)
        idempotency.complete(key, run.runId, terminal = true)
        return run
    }

    private fun line(reservationId: String, paymentIntentId: String, net: Long, source: String): SettlementLine {
        val fee = floor(net * FEE_RATE).toLong()
        return SettlementLine(
            reservationId = reservationId,
            paymentIntentId = paymentIntentId,
            recognizedNetWon = net,
            feeWon = fee,
            ownerPayoutWon = net - fee,
            source = source,
        )
    }

    private fun fail(key: String, propertyId: String, period: YearMonth, message: String): SettlementRun {
        val run =
            SettlementRun(
                runId = UUID.randomUUID().toString(),
                propertyId = propertyId,
                period = period,
                status = SettlementRunStatus.FAILED,
                ownerPayoutWon = 0L,
                lines = emptyList(),
                errorCode = "DUPLICATE_LINE",
                idempotencyKey = key,
            )
        runs.save(run)
        // Non-terminal so a corrected re-run can acquire again after fixing data — but brief says Failed.
        // Keep terminal so identical key returns same Failed run.
        idempotency.complete(key, run.runId, terminal = true)
        return run
    }

    companion object {
        const val FEE_RATE = 0.15
    }
}

data class SettlementLine(
    val reservationId: String,
    val paymentIntentId: String,
    val recognizedNetWon: Long,
    val feeWon: Long,
    val ownerPayoutWon: Long,
    val source: String,
)

data class SettlementRun(
    val runId: String,
    val propertyId: String,
    val period: YearMonth,
    val status: SettlementRunStatus,
    val ownerPayoutWon: Long,
    val lines: List<SettlementLine>,
    val errorCode: String?,
    val idempotencyKey: String,
)

enum class SettlementRunStatus {
    SUCCEEDED,
    FAILED,
}

interface SettlementRunRepository {
    fun save(run: SettlementRun): SettlementRun

    fun findByKey(idempotencyKey: String): SettlementRun?
}
