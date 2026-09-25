package co.handys.booking.payment.infrastructure

import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

/** In-memory profile: marks transactions active without a real datasource (ADR-003 tests / MVP). */
class ResourcelessTransactionManager : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()

    override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

    @Throws(TransactionException::class)
    override fun doCommit(status: DefaultTransactionStatus) = Unit

    @Throws(TransactionException::class)
    override fun doRollback(status: DefaultTransactionStatus) = Unit
}
