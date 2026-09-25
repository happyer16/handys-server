package co.handys.inventory.fake

import co.handys.common.domain.ReasonCode
import co.handys.inventory.api.ConfirmNightsCommand
import co.handys.inventory.api.ConfirmNightsResult
import co.handys.inventory.api.DayQuote
import co.handys.inventory.api.DayQuoteQuery
import co.handys.inventory.api.HoldCommand
import co.handys.inventory.api.HoldResult
import co.handys.inventory.api.InventoryApi
import co.handys.inventory.api.ReleaseNightsCommand
import co.handys.inventory.domain.GateInput
import co.handys.inventory.domain.OverbookGate
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Test-only inventory double (not used in production). */
class FakeInventoryService : InventoryApi {
    private val holds = ConcurrentHashMap<String, HoldRecord>()
    private val slotToHoldId = ConcurrentHashMap<String, String>()
    private val soldByNight = ConcurrentHashMap<String, Int>()

    override fun hold(cmd: HoldCommand): HoldResult {
        val slot = "${cmd.propertyId}:${cmd.roomTypeOrUnitId}:${cmd.checkIn}:${cmd.checkOut}:${cmd.mode}"
        val holdId = UUID.randomUUID().toString()
        if (slotToHoldId.putIfAbsent(slot, holdId) != null) {
            throw IllegalStateException("inventory slot already held")
        }
        holds[holdId] = HoldRecord(cmd, "HELD")
        return HoldResult(holdId, cmd.expiresAt)
    }

    override fun confirmHold(holdId: String) {
        val record = holds[holdId] ?: throw IllegalArgumentException("unknown holdId: $holdId")
        when (record.status) {
            "CONFIRMED" -> return
            "RELEASED" -> throw IllegalStateException("hold already released: $holdId")
            else -> record.status = "CONFIRMED"
        }
    }

    override fun releaseHold(holdId: String) {
        val record = holds[holdId] ?: throw IllegalArgumentException("unknown holdId: $holdId")
        if (record.status == "RELEASED") return
        record.status = "RELEASED"
        val slot = "${record.cmd.propertyId}:${record.cmd.roomTypeOrUnitId}:${record.cmd.checkIn}:${record.cmd.checkOut}:${record.cmd.mode}"
        slotToHoldId.remove(slot, holdId)
    }

    fun isConfirmed(holdId: String): Boolean = holds[holdId]?.status == "CONFIRMED"

    override fun quoteDay(query: DayQuoteQuery): DayQuote {
        val sold = soldByNight["${query.propertyId}:${query.roomTypeId}:${query.date}"] ?: 0
        val daysUntil = ChronoUnit.DAYS.between(query.today, query.date).toInt()
        if (!query.listPricePresent) {
            return DayQuote(query.propertyId, query.roomTypeId, query.date, 0, sold, 0, false, ReasonCode.PRICE_MISSING, daysUntil)
        }
        if (!query.inventorySyncFresh) {
            return DayQuote(query.propertyId, query.roomTypeId, query.date, 0, sold, 0, false, ReasonCode.INVENTORY_STALE, daysUntil)
        }
        val snap = OverbookGate.evaluate(
            GateInput(query.mode, query.capacity, daysUntil, query.minLeadDays, query.minCapacityForOverbook, query.overbookRate, sold),
        )
        return DayQuote(
            query.propertyId, query.roomTypeId, query.date, snap.available, sold, snap.maxSellable, snap.gateOk,
            if (snap.available == 0) snap.rejectHint else null, daysUntil,
        )
    }

    override fun confirmHotelNights(cmd: ConfirmNightsCommand): ConfirmNightsResult {
        val nights = mutableListOf<LocalDate>()
        var d = cmd.checkIn
        while (d.isBefore(cmd.checkOut)) {
            nights += d
            d = d.plusDays(1)
        }
        for (night in nights) {
            val q = quoteDay(
                DayQuoteQuery(
                    cmd.propertyId, cmd.roomTypeId, night, cmd.mode, cmd.capacity,
                    cmd.minLeadDays, cmd.minCapacityForOverbook, cmd.overbookRate, cmd.today,
                    cmd.listPricePresent, cmd.inventorySyncFresh,
                ),
            )
            if (q.available < 1) return ConfirmNightsResult.Rejected(q.reason ?: ReasonCode.AT_CAPACITY, night)
        }
        nights.forEach { night ->
            soldByNight.merge("${cmd.propertyId}:${cmd.roomTypeId}:$night", 1, Int::plus)
        }
        return ConfirmNightsResult.Accepted
    }

    override fun releaseHotelNights(cmd: ReleaseNightsCommand) {
        var d = cmd.checkIn
        while (d.isBefore(cmd.checkOut)) {
            soldByNight.computeIfPresent("${cmd.propertyId}:${cmd.roomTypeId}:$d") { _, v -> maxOf(0, v - 1) }
            d = d.plusDays(1)
        }
    }

    private data class HoldRecord(val cmd: HoldCommand, var status: String)
}
