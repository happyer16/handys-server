package co.handys.booking.payment.application

import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.inventory.api.InventoryHoldApi
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Cancels a reservation. Full refund only when `cancelAt < checkInStart - 24h` (equality → no refund).
 * Gateway refund runs outside the DB transaction (ADR-003).
 */
class CancelReservationService(
    private val transactions: TransactionTemplate,
    private val gateway: RefundGateway,
    private val reservations: ReservationRepository,
    private val paymentIntents: PaymentIntentRepository,
    private val refunds: RefundRepository,
    private val idempotency: IdempotencyStore,
    private val inventory: InventoryHoldApi,
    private val clock: Clock,
) {
    fun cancel(reservationId: String): CancelReservationResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "ADR-003: cancel must not run inside an outer transaction"
        }
        val prepared = requireNotNull(transactions.execute { prepareCancel(reservationId) })
        if (prepared.refundRequest == null) {
            return prepared.result
        }
        val refundResult = gateway.refund(prepared.refundRequest)
        return requireNotNull(
            transactions.execute {
                finalizeRefund(prepared.cancelEventId, prepared.refundRequest, refundResult, prepared.result)
            },
        )
    }

    private fun prepareCancel(reservationId: String): PreparedCancel {
        val now = clock.instant()
        val reservation = reservations.findById(reservationId)
            ?: throw IllegalArgumentException("unknown reservation: $reservationId")
        if (reservation.status == ReservationStatus.CANCELLED) {
            return PreparedCancel(
                CancelReservationResult(
                    reservationId = reservationId,
                    cancelEventId = existingCancelEventId(reservationId),
                    refundAmountWon = refunds.findByReservationId(reservationId)?.amountWon ?: 0L,
                ),
                refundRequest = null,
                cancelEventId = existingCancelEventId(reservationId),
            )
        }
        if (reservation.status == ReservationStatus.EXPIRED) {
            throw IllegalStateException("cannot cancel expired reservation: $reservationId")
        }

        val cancelEventId = UUID.randomUUID().toString()
        val chargeKey = IdempotencyKeys.chargeFull(reservationId)
        val chargeIntent = paymentIntents.findByIdempotencyKey(chargeKey)
        val refundAmount = refundAmountWon(reservation, chargeIntent?.status, now)

        reservations.save(reservation.copy(status = ReservationStatus.CANCELLED, updatedAt = now))
        inventory.releaseHold(reservation.holdId)

        if (chargeIntent != null &&
            chargeIntent.status != PaymentIntentStatus.Succeeded &&
            chargeIntent.status != PaymentIntentStatus.Cancelled
        ) {
            paymentIntents.save(chargeIntent.markCancelled(now))
        }

        val refundRequest =
            if (refundAmount > 0L) {
                val originalPg =
                    requireNotNull(chargeIntent?.pgPaymentId) {
                        "confirmed reservation $reservationId has no pgPaymentId for refund"
                    }
                RefundRequest(
                    idempotencyKey = IdempotencyKeys.refund(reservationId, cancelEventId),
                    amountWon = refundAmount,
                    reservationId = reservationId,
                    originalPgPaymentId = originalPg,
                )
            } else {
                null
            }

        return PreparedCancel(
            result = CancelReservationResult(
                reservationId = reservationId,
                cancelEventId = cancelEventId,
                refundAmountWon = 0L, // filled after gateway when refunding
            ),
            refundRequest = refundRequest,
            cancelEventId = cancelEventId,
        )
    }

    private fun finalizeRefund(
        cancelEventId: String,
        request: RefundRequest,
        refundResult: RefundResult,
        partial: CancelReservationResult,
    ): CancelReservationResult {
        when (refundResult) {
            is RefundResult.Succeeded -> {
                when (idempotency.begin(request.idempotencyKey)) {
                    BeginResult.Acquired ->
                        idempotency.complete(
                            request.idempotencyKey,
                            """{"status":"REFUNDED","amountWon":"${refundResult.amountWon}"}""",
                            terminal = true,
                        )
                    is BeginResult.Existing -> Unit
                }
                refunds.save(
                    RefundRecord(
                        cancelEventId = cancelEventId,
                        reservationId = request.reservationId,
                        amountWon = refundResult.amountWon,
                        pgRefundId = refundResult.pgRefundId,
                        idempotencyKey = request.idempotencyKey,
                    ),
                )
                return partial.copy(refundAmountWon = refundResult.amountWon)
            }
        }
    }

    private fun refundAmountWon(
        reservation: Reservation,
        chargeStatus: PaymentIntentStatus?,
        cancelAt: Instant,
    ): Long {
        if (chargeStatus != PaymentIntentStatus.Succeeded) return 0L
        val checkInStart = reservation.checkIn.atStartOfDay().toInstant(ZoneOffset.UTC)
        val windowEnd = checkInStart.minus(REFUND_WINDOW)
        // Strictly before checkIn - 24h → full refund. Equality and later → 0.
        return if (cancelAt.isBefore(windowEnd)) reservation.amountWon else 0L
    }

    private fun existingCancelEventId(reservationId: String): String =
        refunds.findByReservationId(reservationId)?.cancelEventId ?: "already-cancelled"

    private data class PreparedCancel(
        val result: CancelReservationResult,
        val refundRequest: RefundRequest?,
        val cancelEventId: String,
    )

    companion object {
        val REFUND_WINDOW: Duration = Duration.ofHours(24)
    }
}

data class CancelReservationResult(
    val reservationId: String,
    val cancelEventId: String,
    val refundAmountWon: Long,
)

data class RefundRecord(
    val cancelEventId: String,
    val reservationId: String,
    val amountWon: Long,
    val pgRefundId: String,
    val idempotencyKey: String,
)

interface RefundRepository {
    fun save(record: RefundRecord): RefundRecord

    fun findByReservationId(reservationId: String): RefundRecord?
}
