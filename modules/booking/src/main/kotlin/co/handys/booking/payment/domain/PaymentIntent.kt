package co.handys.booking.payment.domain

import java.time.Instant

data class PaymentIntent(
    val id: String,
    val reservationId: String,
    val amountWon: Long,
    val idempotencyKey: String,
    val status: PaymentIntentStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pgPaymentId: String? = null,
) {
    fun markProcessing(at: Instant): PaymentIntent {
        requireTransition(from = PaymentIntentStatus.RequiresAction, to = PaymentIntentStatus.Processing, at = at)
        return copy(status = PaymentIntentStatus.Processing, updatedAt = at)
    }

    /** [pgPaymentId] is mandatory: a Succeeded intent with no gateway id cannot be reconciled or refunded. */
    fun markSucceeded(at: Instant, pgPaymentId: String): PaymentIntent {
        requireTransition(from = PaymentIntentStatus.Processing, to = PaymentIntentStatus.Succeeded, at = at)
        require(pgPaymentId.isNotBlank()) { "PaymentIntent $id cannot succeed without a pgPaymentId" }
        return copy(status = PaymentIntentStatus.Succeeded, updatedAt = at, pgPaymentId = pgPaymentId)
    }

    /** Declined charge: the intent goes back to awaiting a payment attempt, it is not terminal. */
    fun markRequiresAction(at: Instant): PaymentIntent {
        requireTransition(from = PaymentIntentStatus.Processing, to = PaymentIntentStatus.RequiresAction, at = at)
        return copy(status = PaymentIntentStatus.RequiresAction, updatedAt = at)
    }

    fun markCancelled(at: Instant): PaymentIntent {
        requireTransition(
            from = setOf(PaymentIntentStatus.RequiresAction, PaymentIntentStatus.Processing),
            to = PaymentIntentStatus.Cancelled,
            at = at,
        )
        return copy(status = PaymentIntentStatus.Cancelled, updatedAt = at)
    }

    private fun requireTransition(from: PaymentIntentStatus, to: PaymentIntentStatus, at: Instant) {
        if (status != from) {
            throw IllegalStateException("Cannot transition PaymentIntent $id from $status to $to at $at")
        }
    }

    private fun requireTransition(from: Set<PaymentIntentStatus>, to: PaymentIntentStatus, at: Instant) {
        if (status !in from) {
            throw IllegalStateException("Cannot transition PaymentIntent $id from $status to $to at $at")
        }
    }

    companion object {
        fun create(
            id: String,
            reservationId: String,
            amountWon: Long,
            idempotencyKey: String,
            expiresAt: Instant,
            now: Instant,
        ): PaymentIntent =
            PaymentIntent(
                id = id,
                reservationId = reservationId,
                amountWon = amountWon,
                idempotencyKey = idempotencyKey,
                status = PaymentIntentStatus.RequiresAction,
                expiresAt = expiresAt,
                createdAt = now,
                updatedAt = now,
            )
    }
}
