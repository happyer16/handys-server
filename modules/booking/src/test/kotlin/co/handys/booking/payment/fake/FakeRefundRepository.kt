package co.handys.booking.payment.fake

import co.handys.booking.payment.application.RefundRecord
import co.handys.booking.payment.application.RefundRepository
import java.util.concurrent.ConcurrentHashMap

class FakeRefundRepository : RefundRepository {
    private val byReservation = ConcurrentHashMap<String, RefundRecord>()

    override fun save(record: RefundRecord): RefundRecord {
        byReservation[record.reservationId] = record
        return record
    }

    override fun findByReservationId(reservationId: String): RefundRecord? = byReservation[reservationId]
}
