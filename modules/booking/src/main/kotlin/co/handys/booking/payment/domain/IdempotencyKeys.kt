package co.handys.booking.payment.domain

object IdempotencyKeys {
    fun chargeFull(reservationId: String): String = "pay:$reservationId:CHARGE_FULL"

    fun refund(reservationId: String, cancelEventId: String): String = "ref:$reservationId:$cancelEventId"
}
