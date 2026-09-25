package co.handys.checkin.domain

import co.handys.booking.api.StayReservationView
import co.handys.common.domain.SellMode
import co.handys.property.api.UnitView
import java.time.LocalDate

data class ReadinessEval(
    val flags: Map<String, String>,
    val allGo: Boolean,
    val firstBlocker: String?,
    val checkinEligible: Boolean,
)

object ReadinessEvaluator {
    private val flagOrder = listOf(
        "payment_ok",
        "identity_verified",
        "inventory_ok",
        "unit_assigned",
        "room_ready",
        "key_issuable",
    )

    fun evaluate(
        reservation: StayReservationView,
        mode: SellMode,
        assignedUnit: UnitView?,
        readyPoolCount: Int,
        today: LocalDate,
        keyIssuable: Boolean = true,
    ): ReadinessEval {
        val checkinEligible =
            !today.isBefore(reservation.checkIn) && today.isBefore(reservation.checkOut)
        val flags = mapOf(
            "payment_ok" to if (reservation.paymentStatus == "PAID") "Go" else "No-Go",
            "identity_verified" to if (reservation.identityVerified) "Go" else "No-Go",
            "inventory_ok" to when (mode) {
                SellMode.SPECIFIC_UNIT -> if (reservation.unitId != null) "Go" else "No-Go"
                SellMode.HOTEL_POOL ->
                    if (reservation.unitId != null || readyPoolCount >= 1) "Go" else "No-Go"
            },
            "unit_assigned" to if (reservation.unitId != null) "Go" else "No-Go",
            "room_ready" to if (assignedUnit?.hkStatus == "Ready") "Go" else "No-Go",
            "key_issuable" to if (keyIssuable) "Go" else "No-Go",
        )
        val firstBlocker = flagOrder.firstOrNull { flags[it] == "No-Go" }
        return ReadinessEval(flags, flagOrder.all { flags[it] == "Go" }, firstBlocker, checkinEligible)
    }
}
