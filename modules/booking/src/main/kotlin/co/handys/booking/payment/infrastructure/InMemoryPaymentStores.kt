package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.BeginResult
import co.handys.booking.payment.application.IdempotencyEntry
import co.handys.booking.payment.application.IdempotencyStore
import co.handys.booking.payment.application.PaymentIntentRepository
import co.handys.booking.payment.domain.PaymentIntent
import java.util.concurrent.ConcurrentHashMap

class InMemoryIdempotencyStore : IdempotencyStore {
    private val entries = ConcurrentHashMap<String, IdempotencyEntry>()

    override fun begin(key: String): BeginResult {
        val inFlight = IdempotencyEntry(key = key, payload = null, terminal = false)
        val existing = entries.putIfAbsent(key, inFlight)
        return if (existing == null) {
            BeginResult.Acquired
        } else {
            BeginResult.Existing(existing)
        }
    }

    override fun complete(key: String, responsePayload: String, terminal: Boolean) {
        val current = entries[key] ?: throw IllegalArgumentException("unknown idempotency key: $key")
        entries[key] = current.copy(payload = responsePayload, terminal = terminal)
    }
}

class InMemoryPaymentIntentRepository : PaymentIntentRepository {
    private val byId = ConcurrentHashMap<String, PaymentIntent>()
    private val idByIdempotencyKey = ConcurrentHashMap<String, String>()

    override fun save(intent: PaymentIntent): PaymentIntent {
        byId[intent.id] = intent
        idByIdempotencyKey[intent.idempotencyKey] = intent.id
        return intent
    }

    override fun findById(id: String): PaymentIntent? = byId[id]

    override fun findByIdempotencyKey(idempotencyKey: String): PaymentIntent? {
        val id = idByIdempotencyKey[idempotencyKey] ?: return null
        return byId[id]
    }
}
