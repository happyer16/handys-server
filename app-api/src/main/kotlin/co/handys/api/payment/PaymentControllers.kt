package co.handys.api.payment

import co.handys.booking.payment.application.ChargePaymentResult
import co.handys.booking.payment.application.ChargePaymentService
import co.handys.booking.payment.application.CreateDirectReservationCommand
import co.handys.booking.payment.application.CreateDirectReservationService
import co.handys.booking.payment.application.ExpirePaymentIntentsService
import co.handys.booking.payment.application.HandlePgWebhookService
import co.handys.booking.payment.application.MismatchQueue
import co.handys.booking.payment.application.PostOtaPayoutCommand
import co.handys.booking.payment.application.PostOtaPayoutService
import co.handys.booking.payment.application.RunOwnerSettlementService
import co.handys.booking.payment.domain.PaymentMismatch
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

@RestController
class GuestPaymentController(
    private val createDirect: CreateDirectReservationService,
    private val charge: ChargePaymentService,
) {
    @PostMapping("/guest/reservations")
    fun create(@RequestBody body: CreateReservationRequest) =
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
    fun charge(@PathVariable id: String): ResponseEntity<Any> =
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
class PgWebhookController(
    private val webhooks: HandlePgWebhookService,
) {
    @PostMapping("/webhooks/mock-pg")
    fun mockPg(@RequestBody body: WebhookRequest): ResponseEntity<Void> {
        webhooks.onSuccess(
            pgEventId = body.pgEventId,
            reservationId = body.reservationId,
            pgPaymentId = body.pgPaymentId,
        )
        return ResponseEntity.ok().build()
    }
}

@RestController
class AdminPaymentController(
    private val payouts: PostOtaPayoutService,
    private val settlements: RunOwnerSettlementService,
    private val mismatches: MismatchQueue,
    private val expire: ExpirePaymentIntentsService,
) {
    @PostMapping("/admin/payouts")
    fun postPayout(@RequestBody body: PayoutRequest) =
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
    fun runSettlement(@RequestBody body: SettlementRequest) =
        settlements.run(body.propertyId, YearMonth.parse(body.period))

    @GetMapping("/admin/mismatches")
    fun listMismatches(): List<PaymentMismatch> = mismatches.all()

    @PostMapping("/admin/expire-payments")
    fun expirePayments(): ResponseEntity<Void> {
        expire.runOnce()
        return ResponseEntity.ok().build()
    }
}
