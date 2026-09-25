package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.CancelReservationService
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
import co.handys.booking.payment.application.RefundRepository
import co.handys.booking.payment.application.ReservationRepository
import co.handys.booking.payment.application.RunOwnerSettlementService
import co.handys.booking.payment.application.SettlementBatchJobLock
import co.handys.booking.payment.application.SettlementRunRepository
import co.handys.booking.payment.application.PgEventDedupStore
import co.handys.inventory.api.InventoryApi
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
