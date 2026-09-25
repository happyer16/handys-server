package co.handys.inventory.application

import co.handys.inventory.api.HoldCommand
import co.handys.inventory.api.HoldResult
import co.handys.inventory.api.InventoryApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryInventoryService : InventoryApi {
    private val holds = ConcurrentHashMap<String, HoldRecord>()
    private val slotToHoldId = ConcurrentHashMap<String, String>()

    override fun hold(cmd: HoldCommand): HoldResult {
        val slot = inventorySlot(cmd)
        val holdId = UUID.randomUUID().toString()
        val record = HoldRecord(cmd, HoldStatus.HELD)
        if (slotToHoldId.putIfAbsent(slot, holdId) != null) {
            throw IllegalStateException("inventory slot already held")
        }
        holds[holdId] = record
        return HoldResult(holdId = holdId, expiresAt = cmd.expiresAt)
    }

    override fun confirmHold(holdId: String) {
        val record = holds[holdId] ?: throw IllegalArgumentException("unknown holdId: $holdId")
        when (record.status) {
            HoldStatus.CONFIRMED -> return
            HoldStatus.RELEASED -> throw IllegalStateException("hold already released: $holdId")
            HoldStatus.HELD -> record.status = HoldStatus.CONFIRMED
        }
    }

    override fun releaseHold(holdId: String) {
        val record = holds[holdId] ?: throw IllegalArgumentException("unknown holdId: $holdId")
        if (record.status == HoldStatus.RELEASED) return
        record.status = HoldStatus.RELEASED
        slotToHoldId.remove(inventorySlot(record.cmd), holdId)
    }

    fun isConfirmed(holdId: String): Boolean = holds[holdId]?.status == HoldStatus.CONFIRMED

    private fun inventorySlot(cmd: HoldCommand): String =
        "${cmd.propertyId}:${cmd.roomTypeOrUnitId}:${cmd.checkIn}:${cmd.checkOut}:${cmd.mode}"

    private enum class HoldStatus {
        HELD,
        CONFIRMED,
        RELEASED,
    }

    private data class HoldRecord(
        val cmd: HoldCommand,
        var status: HoldStatus,
    )
}
