package co.handys.booking.payment.domain

enum class PaymentIntentStatus {
    RequiresAction,
    Processing,
    Succeeded,
    Cancelled,
}
