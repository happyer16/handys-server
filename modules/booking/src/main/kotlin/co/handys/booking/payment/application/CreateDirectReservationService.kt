package co.handys.booking.payment.application

import co.handys.booking.domain.PaymentSource
import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntent
import co.handys.common.domain.SellMode
import co.handys.inventory.api.HoldCommand
import co.handys.inventory.api.InventoryHoldApi
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CreateDirectReservationService(
    private val inventory: InventoryHoldApi,
    private val reservations: ReservationRepository,
    private val paymentIntents: PaymentIntentRepository,
    private val clock: Clock,
) {
    fun execute(command: CreateDirectReservationCommand): CreateDirectReservationResult {
        val now = clock.instant()
        val expiresAt = now.plus(PREPARE_TTL)
        val reservationId = UUID.randomUUID().toString()
        val paymentIntentId = UUID.randomUUID().toString()
        val idempotencyKey = IdempotencyKeys.chargeFull(reservationId)
        val hold =
            inventory.hold(
                HoldCommand(
                    propertyId = command.propertyId,
                    roomTypeOrUnitId = command.roomTypeOrUnitId,
                    checkIn = command.checkIn,
                    checkOut = command.checkOut,
                    mode = command.mode,
                    expiresAt = expiresAt,
                ),
            )

        reservations.save(
            Reservation(
                id = reservationId,
                propertyId = command.propertyId,
                roomTypeOrUnitId = command.roomTypeOrUnitId,
                checkIn = command.checkIn,
                checkOut = command.checkOut,
                amountWon = command.amountWon,
                mode = command.mode,
                holdId = hold.holdId,
                status = ReservationStatus.PENDING_PAYMENT,
                paymentSource = PaymentSource.DIRECT,
                expiresAt = expiresAt,
                createdAt = now,
                updatedAt = now,
            ),
        )
        paymentIntents.save(
            PaymentIntent.create(
                id = paymentIntentId,
                reservationId = reservationId,
                amountWon = command.amountWon,
                idempotencyKey = idempotencyKey,
                expiresAt = expiresAt,
                now = now,
            ),
        )

        return CreateDirectReservationResult(
            reservationId = reservationId,
            paymentIntentId = paymentIntentId,
            idempotencyKey = idempotencyKey,
            expiresAt = expiresAt,
        )
    }

    private companion object {
        val PREPARE_TTL: Duration = Duration.ofMinutes(15)
    }
}

data class CreateDirectReservationCommand(
    val propertyId: String,
    val roomTypeOrUnitId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val amountWon: Long,
    val mode: SellMode,
)

data class CreateDirectReservationResult(
    val reservationId: String,
    val paymentIntentId: String,
    val idempotencyKey: String,
    val expiresAt: Instant,
)
