package co.handys.booking.payment.application

/** Charge-only port — cancel/refund callers must not depend on this (ISP). */
interface ChargeGateway {
    fun charge(request: ChargeRequest): ChargeResult
}

/** Refund-only port — charge callers must not depend on this (ISP). */
interface RefundGateway {
    fun refund(request: RefundRequest): RefundResult
}

/** Combined PG adapter for wiring / mocks that implement both operations. */
interface PaymentGateway : ChargeGateway, RefundGateway

data class ChargeRequest(
    val idempotencyKey: String,
    val amountWon: Long,
    val reservationId: String,
)

sealed class ChargeResult {
    data class Succeeded(val pgPaymentId: String, val pgEventId: String) : ChargeResult()

    data class Declined(val reason: String) : ChargeResult()
}

data class RefundRequest(
    val idempotencyKey: String,
    val amountWon: Long,
    val reservationId: String,
    val originalPgPaymentId: String,
)

sealed class RefundResult {
    data class Succeeded(val pgRefundId: String, val amountWon: Long) : RefundResult()
}
