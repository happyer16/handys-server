package co.handys.inventory.domain

import java.time.LocalDate

/** Inclusive check-in, exclusive check-out night list. */
object StayNights {
    fun of(checkIn: LocalDate, checkOut: LocalDate): List<LocalDate> {
        if (!checkOut.isAfter(checkIn)) return emptyList()
        val out = mutableListOf<LocalDate>()
        var d = checkIn
        while (d.isBefore(checkOut)) {
            out += d
            d = d.plusDays(1)
        }
        return out
    }
}
