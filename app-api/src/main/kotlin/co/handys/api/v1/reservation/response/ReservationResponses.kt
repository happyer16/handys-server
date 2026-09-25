package co.handys.api.v1.reservation.response

import co.handys.checkin.api.ReadinessView
import java.time.LocalDate

data class ReservationDetailResponse(
    val id: String,
    val status: String,
    val paymentStatus: String,
    val identityVerified: Boolean,
    val unitId: String?,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val readiness: ReadinessView?,
)

data class KeyIssueResponse(
    val issued: Boolean,
    val keyCode: String?,
    val blocker: String?,
)
