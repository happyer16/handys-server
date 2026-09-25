package co.handys.checkin.api

import co.handys.common.domain.ReasonCode

data class ReadinessView(
    val reservationId: String,
    val flags: Map<String, String>,
    val allGo: Boolean,
    val firstBlocker: String?,
    val checkinEligible: Boolean,
    val keyIssued: Boolean,
)

sealed class KeyResult {
    data class Issued(val keyCode: String) : KeyResult()
    data class Blocked(val reason: ReasonCode, val blocker: String?) : KeyResult()
}

interface CheckinApi {
    fun getReadiness(reservationId: String, today: java.time.LocalDate): ReadinessView?
    fun assignHotelUnit(reservationId: String, today: java.time.LocalDate): ReadinessView?
    fun issueKey(reservationId: String, today: java.time.LocalDate): KeyResult
}
