package co.handys.booking.payment.application

import co.handys.booking.payment.domain.PaymentMismatch

interface MismatchQueue {
    fun enqueue(mismatch: PaymentMismatch)

    fun size(): Int

    fun all(): List<PaymentMismatch>
}

interface PgEventDedupStore {
    /** @return true if this is the first time [pgEventId] is seen. */
    fun tryRecord(pgEventId: String): Boolean

    fun recordedCount(): Int
}
