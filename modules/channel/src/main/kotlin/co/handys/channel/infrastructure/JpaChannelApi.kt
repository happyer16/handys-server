package co.handys.channel.infrastructure

import co.handys.booking.api.BookingApi
import co.handys.booking.api.CreateHotelStayCommand
import co.handys.booking.api.StayBookingResult
import co.handys.channel.api.ChannelApi
import co.handys.channel.api.ChannelBookResult
import co.handys.channel.api.ChannelQuoteRequest
import co.handys.common.domain.ReasonCode
import co.handys.inventory.api.DayQuote
import co.handys.inventory.api.DayQuoteQuery
import co.handys.inventory.api.InventoryQuoteApi
import co.handys.property.api.PropertyApi
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "channel_sync")
class ChannelSyncEntity(
    @Id @Column(name = "channel_id", length = 64) var channelId: String = "",
    @Column(name = "last_sync_at", nullable = false) var lastSyncAt: Instant = Instant.EPOCH,
)

interface ChannelSyncJpaRepository : JpaRepository<ChannelSyncEntity, String>

@Service
class JpaChannelApi(
    private val inventoryQuoteApi: InventoryQuoteApi,
    private val bookingApi: BookingApi,
    private val propertyApi: PropertyApi,
    private val syncRepo: ChannelSyncJpaRepository,
    private val clock: Clock,
) : ChannelApi {
    @Transactional(readOnly = true)
    override fun quote(channelId: String, request: ChannelQuoteRequest, today: LocalDate): DayQuote? {
        val room = propertyApi.getRoomType(request.roomTypeId) ?: return null
        val property = propertyApi.getProperty(request.propertyId) ?: return null
        return inventoryQuoteApi.quoteDay(
            DayQuoteQuery(
                request.propertyId, request.roomTypeId, request.date, room.mode, room.capacity,
                room.minLeadDays, room.minCapacityForOverbook, room.overbookRate, today,
                room.listPrice != null, isFresh(channelId, property.staleTtlSec),
            ),
        )
    }

    @Transactional
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
                    propertyId, roomTypeId, channelId, checkIn, checkOut,
                    guestName, guestPhone, guestEmail,
                    isFresh(channelId, property.staleTtlSec), today,
                ),
            )
        ) {
            is StayBookingResult.Ok -> ChannelBookResult.Ok(result.reservation)
            is StayBookingResult.Rejected -> ChannelBookResult.Rejected(result.reason)
        }
    }

    @Transactional
    override fun markSynced(channelId: String) {
        val row = syncRepo.findById(channelId).orElse(ChannelSyncEntity(channelId))
        row.lastSyncAt = clock.instant()
        syncRepo.save(row)
    }

    private fun isFresh(channelId: String, staleTtlSec: Int): Boolean {
        val at = syncRepo.findById(channelId).map { it.lastSyncAt }.orElse(null) ?: return false
        return Duration.between(at, clock.instant()).seconds <= staleTtlSec
    }
}
