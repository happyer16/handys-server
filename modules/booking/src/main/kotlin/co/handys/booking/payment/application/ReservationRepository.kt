package co.handys.booking.payment.application

import co.handys.booking.domain.Reservation

interface ReservationRepository {
    fun save(reservation: Reservation): Reservation

    fun findById(id: String): Reservation?
}
