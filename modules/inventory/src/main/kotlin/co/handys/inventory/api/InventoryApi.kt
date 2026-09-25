package co.handys.inventory.api

import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import java.time.Instant
import java.time.LocalDate

/**
 * Payment held lifecycle (prepare / confirm / release).
 * Clients that only touch held inventory depend on this — not on quote or sold allocation (ISP).
 */
interface InventoryHoldApi {
    fun hold(cmd: HoldCommand): HoldResult

    fun confirmHold(holdId: String)

    fun releaseHold(holdId: String)
}

/** Day-level availability projection (OverbookGate). */
interface InventoryQuoteApi {
    fun quoteDay(query: DayQuoteQuery): DayQuote?
}

/** Hotel-pool confirmed sold nights (CMS / channel stay path). */
interface InventorySoldApi {
    fun confirmHotelNights(cmd: ConfirmNightsCommand): ConfirmNightsResult

    fun releaseHotelNights(cmd: ReleaseNightsCommand)
}

/**
 * Full inventory facade for wiring / tests that need every surface.
 * Prefer the role interfaces ([InventoryHoldApi], [InventoryQuoteApi], [InventorySoldApi]) at call sites.
 */
interface InventoryApi : InventoryHoldApi, InventoryQuoteApi, InventorySoldApi

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
