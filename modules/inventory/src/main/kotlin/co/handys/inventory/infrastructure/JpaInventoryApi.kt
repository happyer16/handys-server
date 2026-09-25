package co.handys.inventory.infrastructure

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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class JpaInventoryApi(
    private val soldRepo: InventorySoldJpaRepository,
    private val holdRepo: InventoryHoldJpaRepository,
) : InventoryApi {
    @Transactional
    override fun hold(cmd: HoldCommand): HoldResult {
        val slot = "${cmd.propertyId}:${cmd.roomTypeOrUnitId}:${cmd.checkIn}:${cmd.checkOut}:${cmd.mode}"
        if (holdRepo.findBySlotKey(slot) != null) {
            throw IllegalStateException("inventory slot already held")
        }
        val holdId = UUID.randomUUID().toString()
        holdRepo.save(
            InventoryHoldEntity(
                holdId = holdId,
                slotKey = slot,
                propertyId = cmd.propertyId,
                roomTypeOrUnitId = cmd.roomTypeOrUnitId,
                checkIn = cmd.checkIn,
                checkOut = cmd.checkOut,
                mode = cmd.mode,
                expiresAt = cmd.expiresAt,
                status = "HELD",
            ),
        )
        return HoldResult(holdId, cmd.expiresAt)
    }

    @Transactional
    override fun confirmHold(holdId: String) {
        val hold = holdRepo.findById(holdId).orElseThrow { IllegalArgumentException("unknown holdId: $holdId") }
        when (hold.status) {
            "CONFIRMED" -> return
            "RELEASED" -> throw IllegalStateException("hold already released: $holdId")
            else -> {
                hold.status = "CONFIRMED"
                holdRepo.save(hold)
            }
        }
    }

    @Transactional
    override fun releaseHold(holdId: String) {
        val hold = holdRepo.findById(holdId).orElseThrow { IllegalArgumentException("unknown holdId: $holdId") }
        if (hold.status == "RELEASED") return
        hold.status = "RELEASED"
        holdRepo.save(hold)
        holdRepo.delete(hold)
    }

    @Transactional(readOnly = true)
    override fun quoteDay(query: DayQuoteQuery): DayQuote {
        if (!query.listPricePresent) return emptyQuote(query, ReasonCode.PRICE_MISSING)
        if (!query.inventorySyncFresh) return emptyQuote(query, ReasonCode.INVENTORY_STALE)
        val sold = soldCount(query.propertyId, query.roomTypeId, query.date)
        val daysUntil = ChronoUnit.DAYS.between(query.today, query.date).toInt()
        val snap = OverbookGate.evaluate(
            GateInput(
                query.mode, query.capacity, daysUntil,
                query.minLeadDays, query.minCapacityForOverbook, query.overbookRate, sold,
            ),
        )
        return DayQuote(
            query.propertyId, query.roomTypeId, query.date,
            snap.available, sold, snap.maxSellable, snap.gateOk,
            if (snap.available == 0) snap.rejectHint else null, daysUntil,
        )
    }

    @Transactional
    override fun confirmHotelNights(cmd: ConfirmNightsCommand): ConfirmNightsResult {
        if (!cmd.listPricePresent) return ConfirmNightsResult.Rejected(ReasonCode.PRICE_MISSING, null)
        if (!cmd.inventorySyncFresh) return ConfirmNightsResult.Rejected(ReasonCode.INVENTORY_STALE, null)
        val nights = nightsOf(cmd.checkIn, cmd.checkOut)
        if (nights.isEmpty()) return ConfirmNightsResult.Rejected(ReasonCode.BAD_REQUEST, null)
        for (night in nights) {
            val q = quoteDay(
                DayQuoteQuery(
                    cmd.propertyId, cmd.roomTypeId, night, cmd.mode, cmd.capacity,
                    cmd.minLeadDays, cmd.minCapacityForOverbook, cmd.overbookRate, cmd.today,
                    listPricePresent = true, inventorySyncFresh = true,
                ),
            )
            if (q.available < 1) {
                return ConfirmNightsResult.Rejected(q.reason ?: ReasonCode.AT_CAPACITY, night)
            }
        }
        for (night in nights) {
            val id = soldId(cmd.propertyId, cmd.roomTypeId, night)
            var row = soldRepo.findForUpdate(id)
            if (row == null) {
                soldRepo.save(InventorySoldEntity(id, cmd.propertyId, cmd.roomTypeId, night, 0))
                row = soldRepo.findForUpdate(id)!!
            }
            row.sold += 1
            soldRepo.save(row)
        }
        return ConfirmNightsResult.Accepted
    }

    @Transactional
    override fun releaseHotelNights(cmd: ReleaseNightsCommand) {
        for (night in nightsOf(cmd.checkIn, cmd.checkOut)) {
            val id = soldId(cmd.propertyId, cmd.roomTypeId, night)
            val row = soldRepo.findForUpdate(id) ?: continue
            row.sold = maxOf(0, row.sold - 1)
            soldRepo.save(row)
        }
    }

    fun seedSold(propertyId: String, roomTypeId: String, date: LocalDate, sold: Int) {
        val id = soldId(propertyId, roomTypeId, date)
        soldRepo.save(InventorySoldEntity(id, propertyId, roomTypeId, date, sold))
    }

    private fun soldCount(propertyId: String, roomTypeId: String, date: LocalDate): Int =
        soldRepo.findById(soldId(propertyId, roomTypeId, date)).map { it.sold }.orElse(0)

    private fun soldId(propertyId: String, roomTypeId: String, date: LocalDate) =
        "$propertyId:$roomTypeId:$date"

    private fun emptyQuote(query: DayQuoteQuery, reason: ReasonCode) = DayQuote(
        query.propertyId, query.roomTypeId, query.date, 0,
        soldCount(query.propertyId, query.roomTypeId, query.date),
        0, false, reason, ChronoUnit.DAYS.between(query.today, query.date).toInt(),
    )

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
}
