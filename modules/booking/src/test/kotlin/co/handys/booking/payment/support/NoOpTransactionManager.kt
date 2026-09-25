package co.handys.booking.payment.support

import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

/**
 * Transaction manager without a datasource. It performs no persistence work, but it drives the real
 * `TransactionSynchronizationManager` bookkeeping, so `isActualTransactionActive()` answers truthfully
 * inside and outside a `TransactionTemplate` block. That is what lets the tests prove ADR-003's gate:
 * `PaymentGateway` is never reached while a transaction is active.
 */
class NoOpTransactionManager : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()

    override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

    override fun doCommit(status: DefaultTransactionStatus) = Unit

    override fun doRollback(status: DefaultTransactionStatus) = Unit
}
