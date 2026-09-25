package co.handys.api.payment

import co.handys.booking.payment.application.ChargePaymentResult
import co.handys.booking.payment.application.ChargePaymentService
import co.handys.booking.payment.application.CreateDirectReservationCommand
import co.handys.booking.payment.application.CreateDirectReservationResult
import co.handys.booking.payment.application.CreateDirectReservationService
import co.handys.booking.payment.application.ExpirePaymentIntentsService
import co.handys.booking.payment.application.HandlePgWebhookService
import co.handys.booking.payment.application.MismatchQueue
import co.handys.booking.payment.application.OtaPayout
import co.handys.booking.payment.application.PostOtaPayoutCommand
import co.handys.booking.payment.application.PostOtaPayoutService
import co.handys.booking.payment.application.RunOwnerSettlementService
import co.handys.booking.payment.application.SettlementRun
import co.handys.booking.payment.domain.PaymentMismatch
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

@RestController
@Tag(name = "Guest Payment")
class GuestPaymentController(
    private val createDirect: CreateDirectReservationService,
    private val charge: ChargePaymentService,
) {
    @PostMapping("/guest/reservations")
    @Operation(
        summary = "다이렉트 예약 생성 (Prepare TX)",
        description =
            "재고 hold + PENDING_PAYMENT + PaymentIntent(RequiresAction). " +
                "PG는 호출하지 않음. held TTL 15분. 멱등 키 `pay:{reservationId}:CHARGE_FULL` 고정.",
    )
    @ApiResponse(responseCode = "200", description = "held + Intent 생성됨")
    fun create(
        @RequestBody body: CreateReservationRequest,
    ): CreateDirectReservationResult =
        createDirect.execute(
            CreateDirectReservationCommand(
                propertyId = body.propertyId,
                roomTypeOrUnitId = body.roomTypeOrUnitId,
                checkIn = body.checkIn,
                checkOut = body.checkOut,
                amountWon = body.amountWon,
                mode = body.mode,
            ),
        )

    @PostMapping("/guest/reservations/{id}/charge")
    @Operation(
        summary = "다이렉트 전액 청구 (멱등)",
        description =
            "ADR-003: TX-Prepare(멱등 게이트) → Mock PG(트랜잭션 밖) → TX-Finalize(Succeeded+CONFIRMED+confirmHold). " +
                "동일 키 재시도는 청구 1회. Decline은 non-terminal이라 TTL 내 재시도 가능.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "JustSucceeded / AlreadySucceeded"),
        ApiResponse(responseCode = "400", description = "Expired (INTENT_EXPIRED)"),
        ApiResponse(responseCode = "409", description = "InProgress 또는 LateSuccessMismatch"),
        ApiResponse(responseCode = "422", description = "Declined"),
    )
    fun charge(
        @Parameter(description = "예약 ID", required = true) @PathVariable id: String,
    ): ResponseEntity<ChargePaymentResult> =
        when (val result = charge.charge(id)) {
            is ChargePaymentResult.JustSucceeded,
            is ChargePaymentResult.AlreadySucceeded,
            -> ResponseEntity.ok(result)

            is ChargePaymentResult.Declined -> ResponseEntity.unprocessableEntity().body(result)
            is ChargePaymentResult.InProgress -> ResponseEntity.status(409).body(result)
            is ChargePaymentResult.Expired -> ResponseEntity.badRequest().body(result)
            is ChargePaymentResult.LateSuccessMismatch -> ResponseEntity.status(409).body(result)
        }
}

@RestController
@Tag(name = "PG Webhook")
class PgWebhookController(
    private val webhooks: HandlePgWebhookService,
) {
    @PostMapping("/webhooks/mock-pg")
    @Operation(
        summary = "Mock PG 성공 웹훅",
        description =
            "동일 `pgEventId`는 1회만 Finalize. 예약이 이미 EXPIRED면 silent confirm 없이 불일치 큐에 적재.",
    )
    @ApiResponse(responseCode = "200", description = "ack (중복이어도 200)")
    fun mockPg(
        @RequestBody body: WebhookRequest,
    ): ResponseEntity<Void> {
        webhooks.onSuccess(
            pgEventId = body.pgEventId,
            reservationId = body.reservationId,
            pgPaymentId = body.pgPaymentId,
        )
        return ResponseEntity.ok().build()
    }
}

@RestController
@Tag(name = "Admin Payment")
class AdminPaymentController(
    private val payouts: PostOtaPayoutService,
    private val settlements: RunOwnerSettlementService,
    private val mismatches: MismatchQueue,
    private val expire: ExpirePaymentIntentsService,
) {
    @PostMapping("/admin/payouts")
    @Operation(
        summary = "OTA 정산 입금 Posted",
        description = "멱등 키 `payout:{channel}:{channelPayoutId}`. 미입금 월에는 오너 정산에 넣지 않음.",
    )
    fun postPayout(
        @RequestBody body: PayoutRequest,
    ): OtaPayout =
        payouts.post(
            PostOtaPayoutCommand(
                channel = body.channel,
                channelPayoutId = body.channelPayoutId,
                propertyId = body.propertyId,
                amountWon = body.amountWon,
                reservationIds = body.reservationIds,
            ),
        )

    @PostMapping("/admin/settlements")
    @Operation(
        summary = "오너 월 정산 런",
        description =
            "키 `stl:{propertyId}:{yyyy-MM}:ps-v1`. 다이렉트=체크아웃 월, OTA=Posted 입금 월. " +
                "수수료 15%. 동일 키 재실행은 같은 runId.",
    )
    fun runSettlement(
        @RequestBody body: SettlementRequest,
    ): SettlementRun = settlements.run(body.propertyId, YearMonth.parse(body.period))

    @GetMapping("/admin/mismatches")
    @Operation(summary = "결제 불일치 큐 조회", description = "예: LATE_SUCCESS_AFTER_EXPIRE")
    fun listMismatches(): List<PaymentMismatch> = mismatches.all()

    @PostMapping("/admin/expire-payments")
    @Operation(
        summary = "결제 TTL 만료 배치 1회",
        description = "PENDING_PAYMENT 중 expiresAt 경과 건 → EXPIRED + Intent Cancelled + hold 해제",
    )
    fun expirePayments(): ResponseEntity<Void> {
        expire.runOnce()
        return ResponseEntity.ok().build()
    }
}
