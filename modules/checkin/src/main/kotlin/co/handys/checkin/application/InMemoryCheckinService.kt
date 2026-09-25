package co.handys.checkin.application

import co.handys.booking.api.BookingApi
import co.handys.checkin.api.CheckinApi
import co.handys.checkin.api.KeyResult
import co.handys.checkin.api.ReadinessView
import co.handys.checkin.domain.ReadinessEvaluator
import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import co.handys.property.api.PropertyApi
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class InMemoryCheckinService(
    private val bookingApi: BookingApi,
    private val propertyApi: PropertyApi,
) : CheckinApi {
    private val keys = ConcurrentHashMap<String, String>()

    override fun getReadiness(reservationId: String, today: LocalDate): ReadinessView? {
        val reservation = bookingApi.getStay(reservationId) ?: return null
        val room = propertyApi.getRoomType(reservation.roomTypeId) ?: return null
        val unit = reservation.unitId?.let { propertyApi.getUnit(it) }
        val readyCount = propertyApi.listUnitsByRoomType(reservation.roomTypeId)
            .count { it.hkStatus == "Ready" && !it.occupied }
        val eval = ReadinessEvaluator.evaluate(
            reservation = reservation,
            mode = room.mode,
            assignedUnit = unit,
            readyPoolCount = readyCount,
            today = today,
        )
        return ReadinessView(
            reservationId = reservation.id,
            flags = eval.flags,
            allGo = eval.allGo,
            firstBlocker = if (!eval.checkinEligible) "checkin_eligible" else eval.firstBlocker,
            checkinEligible = eval.checkinEligible,
            keyIssued = keys.containsKey(reservationId),
        )
    }

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

    override fun issueKey(reservationId: String, today: LocalDate): KeyResult {
        val readiness = getReadiness(reservationId, today)
            ?: return KeyResult.Blocked(ReasonCode.NOT_FOUND, null)
        if (!readiness.checkinEligible) {
            return KeyResult.Blocked(ReasonCode.CHECKIN_WINDOW, "checkin_eligible")
        }
        if (!readiness.allGo) {
            return KeyResult.Blocked(ReasonCode.READINESS_NOT_MET, readiness.firstBlocker)
        }
        val code = keys.getOrPut(reservationId) { "KEY-$reservationId" }
        return KeyResult.Issued(code)
    }
}
