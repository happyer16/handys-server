package co.handys.booking.payment.application

import co.handys.booking.domain.PaymentSource
import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus
import co.handys.common.domain.SellMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Minimal OTA reservation stub — no CHARGE PaymentIntent (policy D). */
class CreateOtaReservationService(
    private val reservations: ReservationRepository,
    private val clock: Clock,
) {
    fun execute(cmd: CreateOtaReservationCommand): Reservation {
        val now = clock.instant()
        val reservation =
            Reservation(
                id = UUID.randomUUID().toString(),
                propertyId = cmd.propertyId,
                roomTypeOrUnitId = cmd.roomTypeOrUnitId,
                checkIn = cmd.checkIn,
                checkOut = cmd.checkOut,
                amountWon = cmd.amountWon,
                mode = cmd.mode,
                holdId = "ota-hold-${UUID.randomUUID()}",
                status = ReservationStatus.CONFIRMED,
                paymentSource = PaymentSource.OTA,
                expiresAt = now,
                createdAt = now,
                updatedAt = now,
            )
        return reservations.save(reservation)
    }
}

data class CreateOtaReservationCommand(
    val propertyId: String,
    val roomTypeOrUnitId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val amountWon: Long,
    val mode: SellMode,
)
