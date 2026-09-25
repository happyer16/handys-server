package co.handys.booking.payment.fake

import co.handys.booking.payment.application.MismatchQueue
import co.handys.booking.payment.application.PgEventDedupStore
import co.handys.booking.payment.domain.PaymentMismatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FakeMismatchQueue : MismatchQueue {
    private val items = CopyOnWriteArrayList<PaymentMismatch>()

    override fun enqueue(mismatch: PaymentMismatch) {
        items.add(mismatch)
    }

    override fun size(): Int = items.size

    override fun all(): List<PaymentMismatch> = items.toList()
}

class FakePgEventDedupStore : PgEventDedupStore {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    override fun tryRecord(pgEventId: String): Boolean = seen.add(pgEventId)

    override fun recordedCount(): Int = seen.size
}
