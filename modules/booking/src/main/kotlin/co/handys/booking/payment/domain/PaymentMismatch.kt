package co.handys.booking.payment.domain

import java.time.Instant

enum class PaymentMismatchReason {
    LATE_SUCCESS_AFTER_EXPIRE,
}

data class PaymentMismatch(
    val reason: PaymentMismatchReason,
    val reservationId: String,
    val pgEventId: String,
    val pgPaymentId: String,
    val createdAt: Instant,
)
