package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.ChargeRequest
import co.handys.booking.payment.application.ChargeResult
import co.handys.booking.payment.application.PaymentGateway
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class MockChargeBehavior {
    SUCCEED,
    DECLINE,
    DELAY,
}

class MockPaymentGateway(
    private val delayMillis: Long = DEFAULT_DELAY_MILLIS,
) : PaymentGateway {
    private val successByIdempotencyKey = ConcurrentHashMap<String, ChargeResult.Succeeded>()

    override fun charge(request: ChargeRequest): ChargeResult {
        successByIdempotencyKey[request.idempotencyKey]?.let { return it }

        when (behaviorHolder.get()) {
            MockChargeBehavior.DECLINE -> return ChargeResult.Declined(MOCK_DECLINE_REASON)
            MockChargeBehavior.DELAY -> Thread.sleep(delayMillis)
            MockChargeBehavior.SUCCEED -> Unit
        }

        return successByIdempotencyKey.computeIfAbsent(request.idempotencyKey) {
            val token = UUID.randomUUID().toString()
            ChargeResult.Succeeded(
                pgPaymentId = "pg_$token",
                pgEventId = "evt_$token",
            )
        }
    }

    companion object {
        private const val DEFAULT_DELAY_MILLIS = 100L
        private const val MOCK_DECLINE_REASON = "mock_declined"

        private val behaviorHolder = ThreadLocal.withInitial { MockChargeBehavior.SUCCEED }

        var nextBehavior: MockChargeBehavior
            get() = behaviorHolder.get()
            set(value) {
                behaviorHolder.set(value)
            }

        fun resetControls() {
            behaviorHolder.remove()
        }
    }
}
