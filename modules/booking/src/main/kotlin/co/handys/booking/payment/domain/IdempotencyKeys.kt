package co.handys.booking.payment.domain

import java.time.YearMonth

object IdempotencyKeys {
    fun chargeFull(reservationId: String): String = "pay:$reservationId:CHARGE_FULL"

    fun refund(reservationId: String, cancelEventId: String): String = "ref:$reservationId:$cancelEventId"

    fun payout(channel: String, channelPayoutId: String): String = "payout:$channel:$channelPayoutId"

    fun settlement(propertyId: String, period: YearMonth): String = "stl:$propertyId:$period:ps-v1"
}
