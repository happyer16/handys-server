package co.handys.inventory.domain

import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import kotlin.math.floor

data class GateInput(
    val mode: SellMode,
    val capacity: Int,
    val daysUntil: Int,
    val minLeadDays: Int,
    val minCapacityForOverbook: Int,
    val overbookRate: Double,
    val sold: Int,
)

data class GateSnapshot(
    val gateOk: Boolean,
    val maxSellable: Int,
    val available: Int,
    val rejectHint: ReasonCode?,
)

/** ADR-001 / PRD §4.1 — pure overbook gate. */
object OverbookGate {
    fun evaluate(input: GateInput): GateSnapshot {
        if (input.mode == SellMode.SPECIFIC_UNIT) {
            val max = 1
            val available = maxOf(0, max - input.sold)
            return GateSnapshot(
                gateOk = false,
                maxSellable = max,
                available = available,
                rejectHint = if (available == 0) ReasonCode.UNIT_UNAVAILABLE else null,
            )
        }

        val leadOk = input.daysUntil >= input.minLeadDays
        val sizeOk = input.capacity >= input.minCapacityForOverbook
        val rateOk = input.overbookRate > 0.0
        val gateOk = leadOk && sizeOk && rateOk
        val maxSellable =
            if (gateOk) floor(input.capacity * (1.0 + input.overbookRate)).toInt()
            else input.capacity
        val available = maxOf(0, maxSellable - input.sold)
        val rejectHint =
            when {
                available > 0 -> null
                input.sold >= input.capacity && !gateOk && !leadOk -> ReasonCode.OVERBOOK_GATE_LEAD
                input.sold >= input.capacity && !gateOk && !sizeOk -> ReasonCode.OVERBOOK_GATE_SIZE
                else -> ReasonCode.AT_CAPACITY
            }
        return GateSnapshot(gateOk, maxSellable, available, rejectHint)
    }
}
