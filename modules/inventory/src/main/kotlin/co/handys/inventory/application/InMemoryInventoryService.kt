package co.handys.inventory.application

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

class InMemoryInventoryService : InventoryApi {
    private val holds = ConcurrentHashMap<String, HoldRecord>()
    private val slotToHoldId = ConcurrentHashMap<String, String>()
    private val soldByNight = ConcurrentHashMap<String, Int>()

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

    fun seedSold(propertyId: String, roomTypeId: String, date: LocalDate, sold: Int) {
        soldByNight[nightKey(propertyId, roomTypeId, date)] = sold
    }

    override fun quoteDay(query: DayQuoteQuery): DayQuote {
        if (!query.listPricePresent) {
            return emptyQuote(query, ReasonCode.PRICE_MISSING)
        }
        if (!query.inventorySyncFresh) {
            return emptyQuote(query, ReasonCode.INVENTORY_STALE)
        }
        val sold = soldByNight[nightKey(query.propertyId, query.roomTypeId, query.date)] ?: 0
        val daysUntil = ChronoUnit.DAYS.between(query.today, query.date).toInt()
        val snap = OverbookGate.evaluate(
            GateInput(
                mode = query.mode,
                capacity = query.capacity,
                daysUntil = daysUntil,
                minLeadDays = query.minLeadDays,
                minCapacityForOverbook = query.minCapacityForOverbook,
                overbookRate = query.overbookRate,
                sold = sold,
            ),
        )
        return DayQuote(
            propertyId = query.propertyId,
            roomTypeId = query.roomTypeId,
            date = query.date,
            available = snap.available,
            sold = sold,
            maxSellable = snap.maxSellable,
            gateOk = snap.gateOk,
            reason = if (snap.available == 0) snap.rejectHint else null,
            daysUntil = daysUntil,
        )
    }

    override fun confirmHotelNights(cmd: ConfirmNightsCommand): ConfirmNightsResult {
        if (!cmd.listPricePresent) return ConfirmNightsResult.Rejected(ReasonCode.PRICE_MISSING, null)
        if (!cmd.inventorySyncFresh) return ConfirmNightsResult.Rejected(ReasonCode.INVENTORY_STALE, null)
        val nights = nightsOf(cmd.checkIn, cmd.checkOut)
        if (nights.isEmpty()) return ConfirmNightsResult.Rejected(ReasonCode.BAD_REQUEST, null)

        for (night in nights) {
            val quote = quoteDay(
                DayQuoteQuery(
                    propertyId = cmd.propertyId,
                    roomTypeId = cmd.roomTypeId,
                    date = night,
                    mode = cmd.mode,
                    capacity = cmd.capacity,
                    minLeadDays = cmd.minLeadDays,
                    minCapacityForOverbook = cmd.minCapacityForOverbook,
                    overbookRate = cmd.overbookRate,
                    today = cmd.today,
                    listPricePresent = true,
                    inventorySyncFresh = true,
                ),
            )
            if (quote.available < 1) {
                return ConfirmNightsResult.Rejected(quote.reason ?: ReasonCode.AT_CAPACITY, night)
            }
        }
        for (night in nights) {
            val key = nightKey(cmd.propertyId, cmd.roomTypeId, night)
            soldByNight.merge(key, 1, Int::plus)
        }
        return ConfirmNightsResult.Accepted
    }

    override fun releaseHotelNights(cmd: ReleaseNightsCommand) {
        for (night in nightsOf(cmd.checkIn, cmd.checkOut)) {
            val key = nightKey(cmd.propertyId, cmd.roomTypeId, night)
            soldByNight.computeIfPresent(key) { _, v -> maxOf(0, v - 1) }
        }
    }

    private fun emptyQuote(query: DayQuoteQuery, reason: ReasonCode): DayQuote {
        val daysUntil = ChronoUnit.DAYS.between(query.today, query.date).toInt()
        return DayQuote(
            propertyId = query.propertyId,
            roomTypeId = query.roomTypeId,
            date = query.date,
            available = 0,
            sold = soldByNight[nightKey(query.propertyId, query.roomTypeId, query.date)] ?: 0,
            maxSellable = 0,
            gateOk = false,
            reason = reason,
            daysUntil = daysUntil,
        )
    }

    private fun nightsOf(checkIn: LocalDate, checkOut: LocalDate): List<LocalDate> {
        if (!checkOut.isAfter(checkIn)) return emptyList()
        val out = mutableListOf<LocalDate>()
        var d = checkIn
        while (d.isBefore(checkOut)) {
            out += d
            d = d.plusDays(1)
        }
        return out
    }

    private fun nightKey(propertyId: String, roomTypeId: String, date: LocalDate) =
        "$propertyId:$roomTypeId:$date"

    private fun inventorySlot(cmd: HoldCommand): String =
        "${cmd.propertyId}:${cmd.roomTypeOrUnitId}:${cmd.checkIn}:${cmd.checkOut}:${cmd.mode}"

    private enum class HoldStatus { HELD, CONFIRMED, RELEASED }

    private data class HoldRecord(val cmd: HoldCommand, var status: HoldStatus)
}
