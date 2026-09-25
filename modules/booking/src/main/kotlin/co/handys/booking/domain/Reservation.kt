package co.handys.booking.domain

import co.handys.common.domain.SellMode
import java.time.Instant
import java.time.LocalDate

data class Reservation(
    val id: String,
    val propertyId: String,
    val roomTypeOrUnitId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val amountWon: Long,
    val mode: SellMode,
    val holdId: String,
    val status: ReservationStatus,
    val paymentSource: PaymentSource,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ReservationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
}

enum class PaymentSource {
    DIRECT,
    OTA,
}
