package co.handys.booking.application

import co.handys.booking.api.BookingApi
import co.handys.booking.api.CreateHotelStayCommand
import co.handys.booking.api.StayBookingResult
import co.handys.booking.api.StayReservationView
import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import co.handys.inventory.api.ConfirmNightsCommand
import co.handys.inventory.api.ConfirmNightsResult
import co.handys.inventory.api.InventoryApi
import co.handys.property.api.PropertyApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryStayBookingService(
    private val inventoryApi: InventoryApi,
    private val propertyApi: PropertyApi,
) : BookingApi {
    private val stays = ConcurrentHashMap<String, StayReservationView>()

    fun seed(reservation: StayReservationView) {
        stays[reservation.id] = reservation
    }

    override fun getStay(id: String) = stays[id]

    override fun createHotelStay(command: CreateHotelStayCommand): StayBookingResult {
        val room = propertyApi.getRoomType(command.roomTypeId)
            ?: return StayBookingResult.Rejected(ReasonCode.NOT_FOUND)
        if (room.mode != SellMode.HOTEL_POOL) {
            return StayBookingResult.Rejected(ReasonCode.BAD_REQUEST)
        }
        when (
            val confirmed = inventoryApi.confirmHotelNights(
                ConfirmNightsCommand(
                    propertyId = command.propertyId,
                    roomTypeId = command.roomTypeId,
                    checkIn = command.checkIn,
                    checkOut = command.checkOut,
                    channelId = command.channelId,
                    mode = room.mode,
                    capacity = room.capacity,
                    minLeadDays = room.minLeadDays,
                    minCapacityForOverbook = room.minCapacityForOverbook,
                    overbookRate = room.overbookRate,
                    today = command.today,
                    listPricePresent = room.listPrice != null,
                    inventorySyncFresh = command.inventorySyncFresh,
                ),
            )
        ) {
            is ConfirmNightsResult.Rejected -> return StayBookingResult.Rejected(confirmed.reason)
            ConfirmNightsResult.Accepted -> Unit
        }
        val view = StayReservationView(
            id = "R-" + UUID.randomUUID().toString().take(8),
            propertyId = command.propertyId,
            roomTypeId = command.roomTypeId,
            unitId = null,
            channelId = command.channelId,
            status = "CONFIRMED",
            checkIn = command.checkIn,
            checkOut = command.checkOut,
            guestName = command.guestName,
            guestPhone = command.guestPhone,
            guestEmail = command.guestEmail,
            paymentStatus = "UNPAID",
            identityVerified = false,
        )
        stays[view.id] = view
        return StayBookingResult.Ok(view)
    }

    override fun markPaid(id: String): StayReservationView? {
        val cur = stays[id] ?: return null
        val next = cur.copy(paymentStatus = "PAID")
        stays[id] = next
        return next
    }

    override fun markIdentityVerified(id: String): StayReservationView? {
        val cur = stays[id] ?: return null
        val next = cur.copy(identityVerified = true)
        stays[id] = next
        return next
    }

    override fun assignUnit(id: String, unitId: String): StayReservationView? {
        val cur = stays[id] ?: return null
        val next = cur.copy(unitId = unitId)
        stays[id] = next
        return next
    }
}
