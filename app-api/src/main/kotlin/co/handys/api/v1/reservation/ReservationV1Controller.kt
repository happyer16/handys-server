package co.handys.api.v1.reservation

import co.handys.api.common.ApiException
import co.handys.api.v1.reservation.response.KeyIssueResponse
import co.handys.api.v1.reservation.response.ReservationDetailResponse
import co.handys.booking.api.BookingApi
import co.handys.checkin.api.CheckinApi
import co.handys.checkin.api.KeyResult
import co.handys.common.domain.ReasonCode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@RestController
@RequestMapping("/api/v1/reservations/{id}")
@Tag(name = "Reservations")
class ReservationV1Controller(
    private val bookingApi: BookingApi,
    private val checkinApi: CheckinApi,
    private val clock: Clock,
) {
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))

    @GetMapping
    @Operation(summary = "예약 상세 + readiness")
    fun get(@PathVariable id: String): ReservationDetailResponse {
        val r = bookingApi.getStay(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ReasonCode.NOT_FOUND, "Reservation not found")
        val readiness = checkinApi.getReadiness(id, today())
        return ReservationDetailResponse(
            id = r.id,
            status = r.status,
            paymentStatus = r.paymentStatus,
            identityVerified = r.identityVerified,
            unitId = r.unitId,
            checkIn = r.checkIn,
            checkOut = r.checkOut,
            readiness = readiness,
        )
    }

    @PostMapping("/pay")
    @Operation(
        summary = "결제 stub (CMS 체크인용)",
        description = "payment_status=PAID. 멱등 Intent 플로우는 Guest Payment 태그 참고.",
    )
    fun pay(@PathVariable id: String): ReservationDetailResponse {
        bookingApi.markPaid(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ReasonCode.NOT_FOUND, "Reservation not found")
        return get(id)
    }

    @PostMapping("/verify-identity")
    @Operation(summary = "본인확인 stub")
    fun verifyIdentity(@PathVariable id: String): ReservationDetailResponse {
        bookingApi.markIdentityVerified(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ReasonCode.NOT_FOUND, "Reservation not found")
        return get(id)
    }

    @PostMapping("/assign")
    @Operation(summary = "호텔형 유닛 배정")
    fun assign(@PathVariable id: String): ReservationDetailResponse {
        checkinApi.assignHotelUnit(id, today())
            ?: throw ApiException(HttpStatus.NOT_FOUND, ReasonCode.NOT_FOUND, "Reservation not found")
        return get(id)
    }

    @PostMapping("/key")
    @Operation(summary = "키 발급 (All-Go일 때만)")
    fun key(@PathVariable id: String): KeyIssueResponse =
        when (val result = checkinApi.issueKey(id, today())) {
            is KeyResult.Issued -> KeyIssueResponse(issued = true, keyCode = result.keyCode, blocker = null)
            is KeyResult.Blocked ->
                throw ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    result.reason,
                    "Key not issuable",
                    blocker = result.blocker,
                )
        }
}
