package co.handys.booking.payment.fake

import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.application.ReservationRepository
import java.util.concurrent.ConcurrentHashMap

class FakeReservationRepository : ReservationRepository {
    private val reservations = ConcurrentHashMap<String, Reservation>()

    override fun save(reservation: Reservation): Reservation {
        reservations[reservation.id] = reservation
        return reservation
    }

    override fun findById(id: String): Reservation? = reservations[id]

    override fun findByStatus(status: ReservationStatus): List<Reservation> =
        reservations.values.filter { it.status == status }

    override fun findDistinctPropertyIds(): Set<String> =
        reservations.values.map { it.propertyId }.toSet()
}
