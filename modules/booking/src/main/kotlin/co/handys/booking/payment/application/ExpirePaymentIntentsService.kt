package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.inventory.api.InventoryApi
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/** Expires PENDING_PAYMENT reservations whose prepare TTL has elapsed (ADR-003 TX-Expire). */
class ExpirePaymentIntentsService(
    private val transactions: TransactionTemplate,
    private val reservations: ReservationRepository,
    private val paymentIntents: PaymentIntentRepository,
    private val inventory: InventoryApi,
    private val clock: Clock,
) {
    fun runOnce() {
        val now = clock.instant()
        val due = reservations.findByStatus(ReservationStatus.PENDING_PAYMENT)
            .filter { !now.isBefore(it.expiresAt) }
        for (reservation in due) {
            transactions.execute { expireOne(reservation.id, now) }
        }
    }

    private fun expireOne(reservationId: String, now: java.time.Instant) {
        val reservation = reservations.findById(reservationId) ?: return
        if (reservation.status != ReservationStatus.PENDING_PAYMENT) return
        if (now.isBefore(reservation.expiresAt)) return

        val key = IdempotencyKeys.chargeFull(reservationId)
        val intent = paymentIntents.findByIdempotencyKey(key)
        if (intent != null &&
            intent.status != PaymentIntentStatus.Succeeded &&
            intent.status != PaymentIntentStatus.Cancelled
        ) {
            paymentIntents.save(intent.markCancelled(now))
        }
        reservations.save(reservation.copy(status = ReservationStatus.EXPIRED, updatedAt = now))
        inventory.releaseHold(reservation.holdId)
    }
}
