package co.handys.checkin.infrastructure

import co.handys.booking.api.BookingApi
import co.handys.checkin.api.CheckinApi
import co.handys.checkin.api.KeyResult
import co.handys.checkin.api.ReadinessView
import co.handys.checkin.domain.ReadinessEvaluator
import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import co.handys.property.api.PropertyApi
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Entity
@Table(name = "checkin_key")
class KeyIssuedEntity(
    @Id @Column(name = "reservation_id", length = 64) var reservationId: String = "",
    @Column(name = "key_code", nullable = false, length = 128) var keyCode: String = "",
)

interface KeyIssuedJpaRepository : JpaRepository<KeyIssuedEntity, String>

@Service
class JpaCheckinApi(
    private val bookingApi: BookingApi,
    private val propertyApi: PropertyApi,
    private val keys: KeyIssuedJpaRepository,
) : CheckinApi {
    @Transactional(readOnly = true)
    override fun getReadiness(reservationId: String, today: LocalDate): ReadinessView? {
        val reservation = bookingApi.getStay(reservationId) ?: return null
        val room = propertyApi.getRoomType(reservation.roomTypeId) ?: return null
        val unit = reservation.unitId?.let { propertyApi.getUnit(it) }
        val readyCount = propertyApi.listUnitsByRoomType(reservation.roomTypeId)
            .count { it.hkStatus == "Ready" && !it.occupied }
        val eval = ReadinessEvaluator.evaluate(reservation, room.mode, unit, readyCount, today)
        return ReadinessView(
            reservationId = reservation.id,
            flags = eval.flags,
            allGo = eval.allGo,
            firstBlocker = if (!eval.checkinEligible) "checkin_eligible" else eval.firstBlocker,
            checkinEligible = eval.checkinEligible,
            keyIssued = keys.existsById(reservationId),
        )
    }

    @Transactional
    override fun assignHotelUnit(reservationId: String, today: LocalDate): ReadinessView? {
        val reservation = bookingApi.getStay(reservationId) ?: return null
        if (reservation.unitId != null) return getReadiness(reservationId, today)
        val room = propertyApi.getRoomType(reservation.roomTypeId) ?: return null
        if (room.mode != SellMode.HOTEL_POOL) return getReadiness(reservationId, today)
        val candidate = propertyApi.listUnitsByRoomType(reservation.roomTypeId)
            .filter { it.hkStatus == "Ready" && !it.occupied }
            .minByOrNull { it.unitId }
            ?: return getReadiness(reservationId, today)
        bookingApi.assignUnit(reservationId, candidate.unitId)
        return getReadiness(reservationId, today)
    }

    @Transactional
    override fun issueKey(reservationId: String, today: LocalDate): KeyResult {
        val readiness = getReadiness(reservationId, today)
            ?: return KeyResult.Blocked(ReasonCode.NOT_FOUND, null)
        if (!readiness.checkinEligible) {
            return KeyResult.Blocked(ReasonCode.CHECKIN_WINDOW, "checkin_eligible")
        }
        if (!readiness.allGo) {
            return KeyResult.Blocked(ReasonCode.READINESS_NOT_MET, readiness.firstBlocker)
        }
        val existing = keys.findById(reservationId)
        if (existing.isPresent) return KeyResult.Issued(existing.get().keyCode)
        val code = "KEY-$reservationId"
        keys.save(KeyIssuedEntity(reservationId, code))
        return KeyResult.Issued(code)
    }
}
