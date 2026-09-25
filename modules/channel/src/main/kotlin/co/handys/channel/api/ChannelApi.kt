package co.handys.channel.api

import co.handys.booking.api.StayReservationView
import co.handys.common.domain.ReasonCode
import co.handys.inventory.api.DayQuote
import java.time.LocalDate

data class ChannelQuoteRequest(
    val propertyId: String,
    val roomTypeId: String,
    val date: LocalDate,
)

sealed class ChannelBookResult {
    data class Ok(val reservation: StayReservationView) : ChannelBookResult()
    data class Rejected(val reason: ReasonCode) : ChannelBookResult()
}

interface ChannelApi {
    fun quote(channelId: String, request: ChannelQuoteRequest, today: LocalDate): DayQuote?
    fun bookHotel(
        channelId: String,
        propertyId: String,
        roomTypeId: String,
        checkIn: LocalDate,
        checkOut: LocalDate,
        guestName: String,
        guestPhone: String,
        guestEmail: String,
        today: LocalDate,
    ): ChannelBookResult
    fun markSynced(channelId: String)
}
