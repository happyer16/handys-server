package co.handys.inventory.api

import co.handys.common.domain.SellMode
import java.time.Instant
import java.time.LocalDate

/** Public facade — single source of truth for sellable inventory. */
interface InventoryApi {
    fun hold(cmd: HoldCommand): HoldResult

    fun confirmHold(holdId: String)

    fun releaseHold(holdId: String)
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
