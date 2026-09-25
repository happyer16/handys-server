package co.handys.booking.payment.support

import co.handys.booking.payment.application.ChargeRequest
import co.handys.booking.payment.application.ChargeResult
import co.handys.booking.payment.application.PaymentGateway
import co.handys.booking.payment.application.RefundRequest
import co.handys.booking.payment.application.RefundResult
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse

/**
 * Records every charge attempt and fails the test if the call happens inside an active transaction
 * (ADR-003: PG calls live in the Non-TX segment).
 */
class TransactionAssertingGateway(
    private val behaviour: (ChargeRequest) -> ChargeResult,
    private val refundBehaviour: (RefundRequest) -> RefundResult = {
        RefundResult.Succeeded(pgRefundId = "rfnd_test", amountWon = it.amountWon)
    },
) : PaymentGateway {
    private val calls = AtomicInteger()
    private val refundCalls = AtomicInteger()

    val chargeCount: Int
        get() = calls.get()

    val refundCount: Int
        get() = refundCalls.get()

    override fun charge(request: ChargeRequest): ChargeResult {
        assertFalse(
            TransactionSynchronizationManager.isActualTransactionActive(),
            "PaymentGateway.charge was invoked inside a database transaction (ADR-003 violation)",
        )
        calls.incrementAndGet()
        return behaviour(request)
    }

    override fun refund(request: RefundRequest): RefundResult {
        assertFalse(
            TransactionSynchronizationManager.isActualTransactionActive(),
            "PaymentGateway.refund was invoked inside a database transaction (ADR-003 violation)",
        )
        refundCalls.incrementAndGet()
        return refundBehaviour(request)
    }
}
