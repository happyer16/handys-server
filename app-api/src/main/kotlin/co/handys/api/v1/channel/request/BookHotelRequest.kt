package co.handys.api.v1.channel.request

import java.time.LocalDate

data class BookHotelRequest(
    val propertyId: String,
    val roomTypeId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val guestName: String,
    val guestPhone: String,
    val guestEmail: String,
)
