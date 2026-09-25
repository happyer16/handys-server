package co.handys.channel.application

import co.handys.booking.api.BookingApi
import co.handys.booking.api.CreateHotelStayCommand
import co.handys.booking.api.StayBookingResult
import co.handys.channel.api.ChannelApi
import co.handys.channel.api.ChannelBookResult
import co.handys.channel.api.ChannelQuoteRequest
import co.handys.common.domain.ReasonCode
import co.handys.inventory.api.DayQuote
import co.handys.inventory.api.DayQuoteQuery
import co.handys.inventory.api.InventoryApi
import co.handys.property.api.PropertyApi
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class InMemoryChannelService(
    private val inventoryApi: InventoryApi,
    private val bookingApi: BookingApi,
    private val propertyApi: PropertyApi,
    private val clock: () -> Instant = { Instant.now() },
) : ChannelApi {
    private val lastSync = ConcurrentHashMap<String, Instant>()

    fun seedSynced(channelId: String, at: Instant = clock()) {
        lastSync[channelId] = at
    }

    override fun quote(channelId: String, request: ChannelQuoteRequest, today: LocalDate): DayQuote? {
        val room = propertyApi.getRoomType(request.roomTypeId) ?: return null
        val property = propertyApi.getProperty(request.propertyId) ?: return null
        return inventoryApi.quoteDay(
            DayQuoteQuery(
                propertyId = request.propertyId,
                roomTypeId = request.roomTypeId,
                date = request.date,
                mode = room.mode,
                capacity = room.capacity,
                minLeadDays = room.minLeadDays,
                minCapacityForOverbook = room.minCapacityForOverbook,
                overbookRate = room.overbookRate,
                today = today,
                listPricePresent = room.listPrice != null,
                inventorySyncFresh = isFresh(channelId, property.staleTtlSec),
            ),
        )
    }

    override fun bookHotel(
        channelId: String,
        propertyId: String,
        roomTypeId: String,
        checkIn: LocalDate,
        checkOut: LocalDate,
        guestName: String,
        guestPhone: String,
        guestEmail: String,
        today: LocalDate,
    ): ChannelBookResult {
        val property = propertyApi.getProperty(propertyId)
            ?: return ChannelBookResult.Rejected(ReasonCode.NOT_FOUND)
        return when (
            val result = bookingApi.createHotelStay(
                CreateHotelStayCommand(
                    propertyId = propertyId,
                    roomTypeId = roomTypeId,
                    channelId = channelId,
                    checkIn = checkIn,
                    checkOut = checkOut,
                    guestName = guestName,
                    guestPhone = guestPhone,
                    guestEmail = guestEmail,
                    inventorySyncFresh = isFresh(channelId, property.staleTtlSec),
                    today = today,
                ),
            )
        ) {
            is StayBookingResult.Ok -> ChannelBookResult.Ok(result.reservation)
            is StayBookingResult.Rejected -> ChannelBookResult.Rejected(result.reason)
        }
    }

    override fun markSynced(channelId: String) {
        lastSync[channelId] = clock()
    }

    private fun isFresh(channelId: String, staleTtlSec: Int): Boolean {
        val at = lastSync[channelId] ?: return false
        return Duration.between(at, clock()).seconds <= staleTtlSec
    }
}
