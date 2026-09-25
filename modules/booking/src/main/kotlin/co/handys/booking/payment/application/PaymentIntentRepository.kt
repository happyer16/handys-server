package co.handys.booking.payment.application

import co.handys.booking.payment.domain.PaymentIntent

interface PaymentIntentRepository {
    fun save(intent: PaymentIntent): PaymentIntent

    fun findById(id: String): PaymentIntent?

    fun findByIdempotencyKey(idempotencyKey: String): PaymentIntent?
}
