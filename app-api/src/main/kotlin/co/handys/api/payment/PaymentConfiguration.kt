package co.handys.api.payment

import co.handys.booking.payment.application.CancelReservationService
import co.handys.booking.payment.application.ChargePaymentService
import co.handys.booking.payment.application.CreateDirectReservationCommand
import co.handys.booking.payment.application.CreateDirectReservationService
import co.handys.booking.payment.application.CreateOtaReservationCommand
import co.handys.booking.payment.application.CreateOtaReservationService
import co.handys.booking.payment.application.ExpirePaymentIntentsService
import co.handys.booking.payment.application.HandlePgWebhookService
import co.handys.booking.payment.application.IdempotencyStore
import co.handys.booking.payment.application.MismatchQueue
import co.handys.booking.payment.application.OtaPayoutRepository
import co.handys.booking.payment.application.PaymentGateway
import co.handys.booking.payment.application.PaymentIntentRepository
import co.handys.booking.payment.application.PostOtaPayoutCommand
import co.handys.booking.payment.application.PostOtaPayoutService
import co.handys.booking.payment.application.RefundRepository
import co.handys.booking.payment.application.ReservationRepository
import co.handys.booking.payment.application.RunOwnerSettlementService
import co.handys.booking.payment.application.SettlementRunRepository
import co.handys.booking.payment.infrastructure.InMemoryIdempotencyStore
import co.handys.booking.payment.infrastructure.InMemoryMismatchQueue
import co.handys.booking.payment.infrastructure.InMemoryOtaPayoutRepository
import co.handys.booking.payment.infrastructure.InMemoryPaymentIntentRepository
import co.handys.booking.payment.infrastructure.InMemoryPgEventDedupStore
import co.handys.booking.payment.infrastructure.InMemoryRefundRepository
import co.handys.booking.payment.infrastructure.InMemoryReservationRepository
import co.handys.booking.payment.infrastructure.InMemorySettlementRunRepository
import co.handys.booking.payment.infrastructure.MockPaymentGateway
import co.handys.booking.payment.infrastructure.ResourcelessTransactionManager
import co.handys.booking.payment.application.PgEventDedupStore
import co.handys.common.domain.SellMode
import co.handys.inventory.api.InventoryApi
import co.handys.inventory.application.InMemoryInventoryService
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

@Configuration
class PaymentConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun inventoryApi(): InventoryApi = InMemoryInventoryService()

    @Bean
    fun reservationRepository(): ReservationRepository = InMemoryReservationRepository()

    @Bean
    fun paymentIntentRepository(): PaymentIntentRepository = InMemoryPaymentIntentRepository()

    @Bean
    fun idempotencyStore(): IdempotencyStore = InMemoryIdempotencyStore()

    @Bean
    fun refundRepository(): RefundRepository = InMemoryRefundRepository()

    @Bean
    fun otaPayoutRepository(): OtaPayoutRepository = InMemoryOtaPayoutRepository()

    @Bean
    fun settlementRunRepository(): SettlementRunRepository = InMemorySettlementRunRepository()

    @Bean
    fun mismatchQueue(): MismatchQueue = InMemoryMismatchQueue()

    @Bean
    fun pgEventDedupStore(): PgEventDedupStore = InMemoryPgEventDedupStore()

    @Bean
    fun paymentGateway(): PaymentGateway = MockPaymentGateway()

    @Bean
    fun platformTransactionManager(): PlatformTransactionManager = ResourcelessTransactionManager()

    @Bean
    fun transactionTemplate(tm: PlatformTransactionManager): TransactionTemplate = TransactionTemplate(tm)

    @Bean
    fun createDirectReservationService(
        inventoryApi: InventoryApi,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        paymentGateway: PaymentGateway,
        clock: Clock,
    ) = CreateDirectReservationService(
        inventory = inventoryApi,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        paymentGateway = paymentGateway,
        clock = clock,
    )

    @Bean
    fun chargePaymentService(
        transactionTemplate: TransactionTemplate,
        paymentGateway: PaymentGateway,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        idempotencyStore: IdempotencyStore,
        inventoryApi: InventoryApi,
        clock: Clock,
    ) = ChargePaymentService(
        transactions = transactionTemplate,
        gateway = paymentGateway,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        idempotency = idempotencyStore,
        inventory = inventoryApi,
        clock = clock,
    )

    @Bean
    fun handlePgWebhookService(
        transactionTemplate: TransactionTemplate,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        idempotencyStore: IdempotencyStore,
        inventoryApi: InventoryApi,
        mismatchQueue: MismatchQueue,
        pgEventDedupStore: PgEventDedupStore,
        clock: Clock,
    ) = HandlePgWebhookService(
        transactions = transactionTemplate,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        idempotency = idempotencyStore,
        inventory = inventoryApi,
        mismatches = mismatchQueue,
        pgEvents = pgEventDedupStore,
        clock = clock,
    )

    @Bean
    fun expirePaymentIntentsService(
        transactionTemplate: TransactionTemplate,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        inventoryApi: InventoryApi,
        clock: Clock,
    ) = ExpirePaymentIntentsService(
        transactions = transactionTemplate,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        inventory = inventoryApi,
        clock = clock,
    )

    @Bean
    fun cancelReservationService(
        transactionTemplate: TransactionTemplate,
        paymentGateway: PaymentGateway,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        refundRepository: RefundRepository,
        idempotencyStore: IdempotencyStore,
        inventoryApi: InventoryApi,
        clock: Clock,
    ) = CancelReservationService(
        transactions = transactionTemplate,
        gateway = paymentGateway,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        refunds = refundRepository,
        idempotency = idempotencyStore,
        inventory = inventoryApi,
        clock = clock,
    )

    @Bean
    fun createOtaReservationService(reservationRepository: ReservationRepository, clock: Clock) =
        CreateOtaReservationService(reservations = reservationRepository, clock = clock)

    @Bean
    fun postOtaPayoutService(
        otaPayoutRepository: OtaPayoutRepository,
        idempotencyStore: IdempotencyStore,
        clock: Clock,
    ) = PostOtaPayoutService(payouts = otaPayoutRepository, idempotency = idempotencyStore, clock = clock)

    @Bean
    fun runOwnerSettlementService(
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        refundRepository: RefundRepository,
        otaPayoutRepository: OtaPayoutRepository,
        settlementRunRepository: SettlementRunRepository,
        idempotencyStore: IdempotencyStore,
    ) = RunOwnerSettlementService(
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        refunds = refundRepository,
        payouts = otaPayoutRepository,
        runs = settlementRunRepository,
        idempotency = idempotencyStore,
    )
}

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
