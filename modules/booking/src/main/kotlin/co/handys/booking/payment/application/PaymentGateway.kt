package co.handys.booking.payment.application

interface PaymentGateway {
    fun charge(request: ChargeRequest): ChargeResult

    fun refund(request: RefundRequest): RefundResult
}

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
