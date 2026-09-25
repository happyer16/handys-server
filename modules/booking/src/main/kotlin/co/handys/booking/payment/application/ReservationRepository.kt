package co.handys.booking.payment.application

import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus

interface ReservationRepository {
    fun save(reservation: Reservation): Reservation

    fun findById(id: String): Reservation?

    fun findByStatus(status: ReservationStatus): List<Reservation>

    fun findDistinctPropertyIds(): Set<String>
}
