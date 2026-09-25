package co.handys.inventory.api

import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import java.time.Instant
import java.time.LocalDate

/** Public facade — single source of truth for sellable inventory. */
interface InventoryApi {
    fun hold(cmd: HoldCommand): HoldResult

    fun confirmHold(holdId: String)

    fun releaseHold(holdId: String)

    /** PRD §4.1 day projection (OverbookGate). */
    fun quoteDay(query: DayQuoteQuery): DayQuote?

    fun confirmHotelNights(cmd: ConfirmNightsCommand): ConfirmNightsResult

    fun releaseHotelNights(cmd: ReleaseNightsCommand)
}

data class HoldCommand(
    val propertyId: String,
    val roomTypeOrUnitId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val mode: SellMode,
    val expiresAt: Instant,
)

data class HoldResult(
    val holdId: String,
    val expiresAt: Instant,
)

data class DayQuoteQuery(
    val propertyId: String,
    val roomTypeId: String,
    val date: LocalDate,
    val mode: SellMode,
    val capacity: Int,
    val minLeadDays: Int,
    val minCapacityForOverbook: Int,
    val overbookRate: Double,
    val today: LocalDate,
    val listPricePresent: Boolean,
    val inventorySyncFresh: Boolean,
)

data class DayQuote(
    val propertyId: String,
    val roomTypeId: String,
    val date: LocalDate,
    val available: Int,
    val sold: Int,
    val maxSellable: Int,
    val gateOk: Boolean,
    val reason: ReasonCode?,
    val daysUntil: Int,
)

data class ConfirmNightsCommand(
    val propertyId: String,
    val roomTypeId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val channelId: String,
    val mode: SellMode,
    val capacity: Int,
    val minLeadDays: Int,
    val minCapacityForOverbook: Int,
    val overbookRate: Double,
    val today: LocalDate,
    val listPricePresent: Boolean,
    val inventorySyncFresh: Boolean,
)

data class ReleaseNightsCommand(
    val propertyId: String,
    val roomTypeId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
)

sealed class ConfirmNightsResult {
    data object Accepted : ConfirmNightsResult()
    data class Rejected(val reason: ReasonCode, val night: LocalDate?) : ConfirmNightsResult()
}
