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
        var result: BeginResult = BeginResult.Acquired
        entries.compute(key) { _, current ->
            when {
                current == null -> inFlight(key)
                // Terminal or still in flight: the caller must not reach the gateway.
                current.terminal || current.inFlight -> {
                    result = BeginResult.Existing(current)
                    current
                }
                // Completed non-terminal (declined): hand the key back so a retry can call the PG again.
                else -> inFlight(key)
            }
        }
        return result
    }

    override fun complete(key: String, responsePayload: String, terminal: Boolean) {
        val current = entries[key] ?: throw IllegalArgumentException("unknown idempotency key: $key")
        entries[key] = current.copy(payload = responsePayload, terminal = terminal, inFlight = false)
    }

    private fun inFlight(key: String) =
        IdempotencyEntry(key = key, payload = null, terminal = false, inFlight = true)
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
