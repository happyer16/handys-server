package co.handys.booking.payment.application

interface PaymentGateway {
    fun charge(request: ChargeRequest): ChargeResult
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
