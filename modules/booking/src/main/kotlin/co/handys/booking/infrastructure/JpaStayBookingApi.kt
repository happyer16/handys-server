package co.handys.booking.infrastructure

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
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "booking_stay")
class StayReservationEntity(
    @Id @Column(name = "id", length = 64) var id: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Column(name = "room_type_id", nullable = false, length = 64) var roomTypeId: String = "",
    @Column(name = "unit_id", length = 64) var unitId: String? = null,
    @Column(name = "channel_id", nullable = false, length = 64) var channelId: String = "",
    @Column(name = "status", nullable = false, length = 32) var status: String = "CONFIRMED",
    @Column(name = "check_in", nullable = false) var checkIn: LocalDate = LocalDate.EPOCH,
    @Column(name = "check_out", nullable = false) var checkOut: LocalDate = LocalDate.EPOCH,
    @Column(name = "guest_name", nullable = false, length = 128) var guestName: String = "",
    @Column(name = "guest_phone", nullable = false, length = 32) var guestPhone: String = "",
    @Column(name = "guest_email", nullable = false, length = 256) var guestEmail: String = "",
    @Column(name = "payment_status", nullable = false, length = 16) var paymentStatus: String = "UNPAID",
    @Column(name = "identity_verified", nullable = false) var identityVerified: Boolean = false,
)

interface StayReservationJpaRepository : JpaRepository<StayReservationEntity, String>

@Service
class JpaStayBookingApi(
    private val stays: StayReservationJpaRepository,
    private val inventoryApi: InventoryApi,
    private val propertyApi: PropertyApi,
) : BookingApi {
    @Transactional(readOnly = true)
    override fun getStay(id: String): StayReservationView? =
        stays.findById(id).map { it.toView() }.orElse(null)

    @Transactional
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
        val entity = StayReservationEntity(
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
        return StayBookingResult.Ok(stays.save(entity).toView())
    }

    @Transactional
    override fun markPaid(id: String): StayReservationView? {
        val e = stays.findById(id).orElse(null) ?: return null
        e.paymentStatus = "PAID"
        return stays.save(e).toView()
    }

    @Transactional
    override fun markIdentityVerified(id: String): StayReservationView? {
        val e = stays.findById(id).orElse(null) ?: return null
        e.identityVerified = true
        return stays.save(e).toView()
    }

    @Transactional
    override fun assignUnit(id: String, unitId: String): StayReservationView? {
        val e = stays.findById(id).orElse(null) ?: return null
        e.unitId = unitId
        return stays.save(e).toView()
    }

    private fun StayReservationEntity.toView() =
        StayReservationView(
            id, propertyId, roomTypeId, unitId, channelId, status, checkIn, checkOut,
            guestName, guestPhone, guestEmail, paymentStatus, identityVerified,
        )
}
