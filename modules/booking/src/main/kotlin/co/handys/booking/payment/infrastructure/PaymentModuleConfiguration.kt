package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.CancelReservationService
import co.handys.booking.payment.application.ChargeGateway
import co.handys.booking.payment.application.ChargePaymentService
import co.handys.booking.payment.application.CreateDirectReservationService
import co.handys.booking.payment.application.CreateOtaReservationService
import co.handys.booking.payment.application.ExpirePaymentIntentsService
import co.handys.booking.payment.application.HandlePgWebhookService
import co.handys.booking.payment.application.IdempotencyStore
import co.handys.booking.payment.application.MismatchQueue
import co.handys.booking.payment.application.OtaPayoutRepository
import co.handys.booking.payment.application.OwnerSettlementBatchService
import co.handys.booking.payment.application.PaymentGateway
import co.handys.booking.payment.application.PaymentIntentRepository
import co.handys.booking.payment.application.PostOtaPayoutService
import co.handys.booking.payment.application.RefundGateway
import co.handys.booking.payment.application.RefundRepository
import co.handys.booking.payment.application.ReservationRepository
import co.handys.booking.payment.application.RunOwnerSettlementService
import co.handys.booking.payment.application.SettlementBatchJobLock
import co.handys.booking.payment.application.SettlementRunRepository
import co.handys.booking.payment.application.PgEventDedupStore
import co.handys.inventory.api.InventoryHoldApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Shared payment use-case beans for app-api and app-batch.
 * Persistence ports are JPA `@Service` implementations discovered via component scan.
 */
@Configuration
class PaymentModuleConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun paymentGateway(): PaymentGateway = MockPaymentGateway()

    @Bean
    fun transactionTemplate(tm: PlatformTransactionManager): TransactionTemplate = TransactionTemplate(tm)

    @Bean
    fun createDirectReservationService(
        inventoryHoldApi: InventoryHoldApi,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        clock: Clock,
    ) = CreateDirectReservationService(
        inventory = inventoryHoldApi,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        clock = clock,
    )

    @Bean
    fun chargePaymentService(
        transactionTemplate: TransactionTemplate,
        chargeGateway: ChargeGateway,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        idempotencyStore: IdempotencyStore,
        inventoryHoldApi: InventoryHoldApi,
        clock: Clock,
    ) = ChargePaymentService(
        transactions = transactionTemplate,
        gateway = chargeGateway,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        idempotency = idempotencyStore,
        inventory = inventoryHoldApi,
        clock = clock,
    )

    @Bean
    fun handlePgWebhookService(
        transactionTemplate: TransactionTemplate,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        idempotencyStore: IdempotencyStore,
        inventoryHoldApi: InventoryHoldApi,
        mismatchQueue: MismatchQueue,
        pgEventDedupStore: PgEventDedupStore,
        clock: Clock,
    ) = HandlePgWebhookService(
        transactions = transactionTemplate,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        idempotency = idempotencyStore,
        inventory = inventoryHoldApi,
        mismatches = mismatchQueue,
        pgEvents = pgEventDedupStore,
        clock = clock,
    )

    @Bean
    fun expirePaymentIntentsService(
        transactionTemplate: TransactionTemplate,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        inventoryHoldApi: InventoryHoldApi,
        clock: Clock,
    ) = ExpirePaymentIntentsService(
        transactions = transactionTemplate,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        inventory = inventoryHoldApi,
        clock = clock,
    )

    @Bean
    fun cancelReservationService(
        transactionTemplate: TransactionTemplate,
        refundGateway: RefundGateway,
        reservationRepository: ReservationRepository,
        paymentIntentRepository: PaymentIntentRepository,
        refundRepository: RefundRepository,
        idempotencyStore: IdempotencyStore,
        inventoryHoldApi: InventoryHoldApi,
        clock: Clock,
    ) = CancelReservationService(
        transactions = transactionTemplate,
        gateway = refundGateway,
        reservations = reservationRepository,
        paymentIntents = paymentIntentRepository,
        refunds = refundRepository,
        idempotency = idempotencyStore,
        inventory = inventoryHoldApi,
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

    @Bean
    fun ownerSettlementBatchService(
        settlementBatchJobLock: SettlementBatchJobLock,
        runOwnerSettlementService: RunOwnerSettlementService,
        reservationRepository: ReservationRepository,
        clock: Clock,
    ) = OwnerSettlementBatchService(
        lock = settlementBatchJobLock,
        settlements = runOwnerSettlementService,
        reservations = reservationRepository,
        clock = clock,
    )
}
