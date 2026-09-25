package co.handys.booking.payment.domain

object IdempotencyKeys {
    fun chargeFull(reservationId: String): String = "pay:$reservationId:CHARGE_FULL"
}
