package co.handys.booking.api

import co.handys.common.domain.ReasonCode
import java.time.LocalDate

data class StayReservationView(
    val id: String,
    val propertyId: String,
    val roomTypeId: String,
    val unitId: String?,
    val channelId: String,
    val status: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val guestName: String,
    val guestPhone: String,
    val guestEmail: String,
    val paymentStatus: String,
    val identityVerified: Boolean,
)

data class CreateHotelStayCommand(
    val propertyId: String,
    val roomTypeId: String,
    val channelId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val guestName: String,
    val guestPhone: String,
    val guestEmail: String,
    val inventorySyncFresh: Boolean,
    val today: LocalDate,
)

sealed class StayBookingResult {
    data class Ok(val reservation: StayReservationView) : StayBookingResult()
    data class Rejected(val reason: ReasonCode) : StayBookingResult()
}

/** CMS stay lifecycle (separate from payment Intent reservation). */
interface BookingApi {
    fun getStay(id: String): StayReservationView?

    /** Alias used by REST layer. */
    fun getReservation(id: String): StayReservationView? = getStay(id)

    fun createHotelStay(command: CreateHotelStayCommand): StayBookingResult
    fun markPaid(id: String): StayReservationView?
    fun markIdentityVerified(id: String): StayReservationView?
    fun assignUnit(id: String, unitId: String): StayReservationView?
}
