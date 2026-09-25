package co.handys.booking.payment.application

import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntent
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.inventory.api.InventoryApi
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * Charges a prepared direct reservation.
 *
 * ADR-003 splits one charge into: short TX (idempotency gate + intent → Processing) → PG call with no
 * transaction open → short TX-Finalize (intent Succeeded, reservation CONFIRMED, hold confirmed, idempotency
 * record made terminal). The gateway call sits between the two `transactions.execute` blocks on purpose: it must
 * never hold a database connection, and a rolled back transaction can never undo a real charge.
 */
class ChargePaymentService(
    private val transactions: TransactionTemplate,
    private val gateway: PaymentGateway,
    private val reservations: ReservationRepository,
    private val paymentIntents: PaymentIntentRepository,
    private val idempotency: IdempotencyStore,
    private val inventory: InventoryApi,
    private val clock: Clock,
) {
    fun charge(reservationId: String): ChargePaymentResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "ADR-003: charge must not run inside an outer transaction"
        }
        val key = IdempotencyKeys.chargeFull(reservationId)

        return when (val prepared = requireNotNull(transactions.execute { prepare(reservationId, key) })) {
            is Preparation.ShortCircuit -> prepared.result
            is Preparation.Proceed -> {
                val gatewayResult = gateway.charge(prepared.request)
                requireNotNull(transactions.execute { finalize(reservationId, key, gatewayResult) })
            }
        }
    }

    /** Short TX. Decides whether this caller is the one allowed to reach the gateway. */
    private fun prepare(reservationId: String, key: String): Preparation {
        val reservation = loadReservation(reservationId)
        val intent = loadIntent(reservationId, key)
        val now = clock.instant()

        if (intent.status == PaymentIntentStatus.Succeeded) {
            val pgPaymentId = requireNotNull(intent.pgPaymentId) { "succeeded intent ${intent.id} has no pgPaymentId" }
            // A paid intent whose reservation was lost is a mismatch, never a success replay.
            return Preparation.ShortCircuit(
                if (isReservationLost(reservation)) {
                    ChargePaymentResult.LateSuccessMismatch(pgPaymentId)
                } else {
                    ChargePaymentResult.AlreadySucceeded(pgPaymentId)
                },
            )
        }
        if (isExpired(reservation, intent, now)) {
            return Preparation.ShortCircuit(ChargePaymentResult.Expired())
        }

        return when (val begun = idempotency.begin(key)) {
            is BeginResult.Existing ->
                Preparation.ShortCircuit(
                    if (begun.entry.terminal) {
                        ChargeResponsePayload.decode(
                            requireNotNull(begun.entry.payload) { "terminal idempotency record $key has no payload" },
                        )
                    } else {
                        ChargePaymentResult.InProgress(key)
                    },
                )

            BeginResult.Acquired -> {
                if (intent.status == PaymentIntentStatus.RequiresAction) {
                    paymentIntents.save(intent.markProcessing(now))
                }
                Preparation.Proceed(
                    ChargeRequest(
                        idempotencyKey = key,
                        amountWon = intent.amountWon,
                        reservationId = reservationId,
                    ),
                )
            }
        }
    }

    /**
     * TX-Finalize. Intent, reservation, hold and the idempotency record move in one commit so that a paid
     * reservation can never be left with inventory still `held`. No gateway call belongs in here.
     *
     * The hold may have expired during the gateway round trip, so the reservation and intent are re-validated
     * against committed state here rather than trusting the decision made in [prepare].
     */
    private fun finalize(reservationId: String, key: String, gatewayResult: ChargeResult): ChargePaymentResult {
        val reservation = loadReservation(reservationId)
        val intent = loadIntent(reservationId, key)
        val now = clock.instant()

        return when (gatewayResult) {
            is ChargeResult.Succeeded -> {
                if (isExpired(reservation, intent, now)) {
                    lateSuccess(key, intent, gatewayResult, now)
                } else {
                    paymentIntents.save(intent.markSucceeded(now, pgPaymentId = gatewayResult.pgPaymentId))
                    reservations.save(reservation.copy(status = ReservationStatus.CONFIRMED, updatedAt = now))
                    inventory.confirmHold(reservation.holdId)
                    idempotency.complete(key, ChargeResponsePayload.encode(gatewayResult), terminal = true)
                    ChargePaymentResult.JustSucceeded(gatewayResult.pgPaymentId)
                }
            }

            is ChargeResult.Declined -> {
                paymentIntents.save(intent.markRequiresAction(now))
                // Non-terminal: no money moved, so a retry inside the TTL is allowed to call the PG again.
                idempotency.complete(key, ChargeResponsePayload.encode(gatewayResult), terminal = false)
                ChargePaymentResult.Declined(gatewayResult.reason)
            }
        }
    }

    /**
     * The PG charged but the reservation is no longer chargeable (ADR-003 `LATE_SUCCESS_AFTER_EXPIRE`).
     * Fail closed: record the captured payment so the money is traceable, but do not confirm the hold and do
     * not silently move the reservation to CONFIRMED. Terminal, because the charge must not be replayed.
     */
    private fun lateSuccess(
        key: String,
        intent: PaymentIntent,
        gatewayResult: ChargeResult.Succeeded,
        now: Instant,
    ): ChargePaymentResult {
        paymentIntents.save(intent.markSucceeded(now, pgPaymentId = gatewayResult.pgPaymentId))
        idempotency.complete(key, ChargeResponsePayload.encodeLateSuccess(gatewayResult), terminal = true)
        return ChargePaymentResult.LateSuccessMismatch(gatewayResult.pgPaymentId)
    }

    private fun isExpired(reservation: Reservation, intent: PaymentIntent, now: Instant): Boolean =
        isReservationLost(reservation) ||
            intent.status == PaymentIntentStatus.Cancelled ||
            !now.isBefore(intent.expiresAt)

    private fun isReservationLost(reservation: Reservation): Boolean =
        reservation.status == ReservationStatus.EXPIRED || reservation.status == ReservationStatus.CANCELLED

    private fun loadReservation(reservationId: String): Reservation =
        reservations.findById(reservationId)
            ?: throw IllegalArgumentException("unknown reservation: $reservationId")

    private fun loadIntent(reservationId: String, key: String): PaymentIntent =
        paymentIntents.findByIdempotencyKey(key)
            ?: throw IllegalStateException("reservation $reservationId has no CHARGE_FULL payment intent")

    private sealed interface Preparation {
        data class Proceed(val request: ChargeRequest) : Preparation

        data class ShortCircuit(val result: ChargePaymentResult) : Preparation
    }
}

sealed class ChargePaymentResult {
    /** Replay of an earlier successful charge. The gateway is not contacted. */
    data class AlreadySucceeded(val pgPaymentId: String) : ChargePaymentResult()

    data class JustSucceeded(val pgPaymentId: String) : ChargePaymentResult()

    data class Declined(val reason: String) : ChargePaymentResult()

    /** Another caller holds the in-flight idempotency record for this key. */
    data class InProgress(val idempotencyKey: String) : ChargePaymentResult()

    data class Expired(val code: String = INTENT_EXPIRED) : ChargePaymentResult()

    /**
     * The PG charged but the reservation had already expired, so nothing was confirmed. The payment is
     * captured and needs the mismatch path (refund or manual confirm) — not a success for the caller.
     */
    data class LateSuccessMismatch(
        val pgPaymentId: String,
        val code: String = LATE_SUCCESS_AFTER_EXPIRE,
    ) : ChargePaymentResult()

    companion object {
        const val INTENT_EXPIRED = "INTENT_EXPIRED"
        const val LATE_SUCCESS_AFTER_EXPIRE = "LATE_SUCCESS_AFTER_EXPIRE"
    }
}
