package co.handys.api.payment

import co.handys.common.domain.SellMode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

data class CreateReservationRequest(
    @field:Schema(description = "지점 ID", example = "prop-it")
    val propertyId: String,
    @field:Schema(description = "룸타입 또는 특정 유닛 ID", example = "deluxe")
    val roomTypeOrUnitId: String,
    @field:Schema(description = "체크인 날짜 (지점 TZ 기준 날짜)", example = "2026-10-01")
    val checkIn: LocalDate,
    @field:Schema(description = "체크아웃 날짜 (반개구간, 이 날은 미포함)", example = "2026-10-03")
    val checkOut: LocalDate,
    @field:Schema(description = "청구 금액(원)", example = "100000")
    val amountWon: Long,
    @field:Schema(description = "판매 모드 (ADR-001)", example = "HOTEL_POOL")
    val mode: SellMode = SellMode.HOTEL_POOL,
)

data class WebhookRequest(
    @field:Schema(description = "PG 이벤트 ID (UNIQUE, 중복 웹훅 차단)", example = "evt_abc")
    val pgEventId: String,
    @field:Schema(description = "예약 ID")
    val reservationId: String,
    @field:Schema(description = "PG 결제 ID", example = "pg_abc")
    val pgPaymentId: String,
)

data class PayoutRequest(
    @field:Schema(description = "채널 id", example = "ota_a")
    val channel: String,
    @field:Schema(description = "채널 정산/입금 ID", example = "po_1")
    val channelPayoutId: String,
    @field:Schema(description = "지점 ID")
    val propertyId: String,
    @field:Schema(description = "입금 금액(원)")
    val amountWon: Long,
    @field:Schema(description = "매핑할 예약 ID 목록")
    val reservationIds: List<String>,
)

data class SettlementRequest(
    @field:Schema(description = "지점 ID", example = "prop-it")
    val propertyId: String,
    @field:Schema(description = "정산 월 (yyyy-MM)", example = "2026-09")
    val period: String,
)
