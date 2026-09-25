package co.handys.booking.payment.application

import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntent
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.domain.PaymentMismatch
import co.handys.booking.payment.domain.PaymentMismatchReason
import co.handys.inventory.api.InventoryApi
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * Applies Mock PG / PG success webhooks. Duplicate [pgEventId] values are no-ops.
 * Late success after expire enqueues a mismatch and never silently confirms (ADR-003).
 */
class HandlePgWebhookService(
    private val transactions: TransactionTemplate,
    private val reservations: ReservationRepository,
    private val paymentIntents: PaymentIntentRepository,
    private val idempotency: IdempotencyStore,
    private val inventory: InventoryApi,
    private val mismatches: MismatchQueue,
    private val pgEvents: PgEventDedupStore,
    private val clock: Clock,
) {
    fun onSuccess(pgEventId: String, reservationId: String, pgPaymentId: String) {
        if (!pgEvents.tryRecord(pgEventId)) {
            return
        }
        transactions.execute {
            applySuccess(pgEventId = pgEventId, reservationId = reservationId, pgPaymentId = pgPaymentId)
        }
    }

    private fun applySuccess(pgEventId: String, reservationId: String, pgPaymentId: String) {
        val now = clock.instant()
        val reservation = reservations.findById(reservationId)
            ?: throw IllegalArgumentException("unknown reservation: $reservationId")
        val key = IdempotencyKeys.chargeFull(reservationId)
        val intent = paymentIntents.findByIdempotencyKey(key)
            ?: throw IllegalStateException("reservation $reservationId has no CHARGE_FULL payment intent")

        if (intent.status == PaymentIntentStatus.Succeeded) {
            return
        }

        if (isUnconfirmable(reservation, intent, now)) {
            recordLateSuccess(reservationId, key, intent, pgEventId, pgPaymentId, now)
            return
        }

        val processing =
            when (intent.status) {
                PaymentIntentStatus.RequiresAction -> intent.markProcessing(now)
                PaymentIntentStatus.Processing -> intent
                PaymentIntentStatus.Cancelled, PaymentIntentStatus.Succeeded ->
                    error("unreachable after Succeeded/unconfirmable guards")
            }
        paymentIntents.save(processing.markSucceeded(now, pgPaymentId = pgPaymentId))
        reservations.save(reservation.copy(status = ReservationStatus.CONFIRMED, updatedAt = now))
        inventory.confirmHold(reservation.holdId)
        ensureIdempotencyTerminal(
            key,
            ChargeResult.Succeeded(pgPaymentId = pgPaymentId, pgEventId = pgEventId),
        )
    }

    private fun recordLateSuccess(
        reservationId: String,
        key: String,
        intent: PaymentIntent,
        pgEventId: String,
        pgPaymentId: String,
        now: Instant,
    ) {
        val processing =
            when (intent.status) {
                PaymentIntentStatus.RequiresAction -> intent.markProcessing(now)
                PaymentIntentStatus.Processing -> intent
                PaymentIntentStatus.Cancelled -> intent // keep Cancelled; still record money via mismatch only
                PaymentIntentStatus.Succeeded -> return
            }
        if (processing.status != PaymentIntentStatus.Cancelled) {
            paymentIntents.save(processing.markSucceeded(now, pgPaymentId = pgPaymentId))
        }
        ensureIdempotencyTerminal(
            key,
            ChargeResult.Succeeded(pgPaymentId = pgPaymentId, pgEventId = pgEventId),
            lateSuccess = true,
        )
        mismatches.enqueue(
            PaymentMismatch(
                reason = PaymentMismatchReason.LATE_SUCCESS_AFTER_EXPIRE,
                reservationId = reservationId,
                pgEventId = pgEventId,
                pgPaymentId = pgPaymentId,
                createdAt = now,
            ),
        )
    }

    private fun ensureIdempotencyTerminal(key: String, result: ChargeResult.Succeeded, lateSuccess: Boolean = false) {
        val payload =
            if (lateSuccess) {
                ChargeResponsePayload.encodeLateSuccess(result)
            } else {
                ChargeResponsePayload.encode(result)
            }
        when (val begun = idempotency.begin(key)) {
            BeginResult.Acquired -> idempotency.complete(key, payload, terminal = true)
            is BeginResult.Existing ->
                if (begun.entry.inFlight) {
                    // Sync charge left the key in-flight; webhook closes it.
                    idempotency.complete(key, payload, terminal = true)
                }
            // Existing terminal → already closed; leave alone.
        }
    }

    private fun isUnconfirmable(reservation: Reservation, intent: PaymentIntent, now: Instant): Boolean =
        reservation.status == ReservationStatus.EXPIRED ||
            reservation.status == ReservationStatus.CANCELLED ||
            intent.status == PaymentIntentStatus.Cancelled ||
            !now.isBefore(intent.expiresAt)
}
