package co.handys.inventory.infrastructure

import co.handys.common.domain.ReasonCode
import co.handys.inventory.api.ConfirmNightsCommand
import co.handys.inventory.api.ConfirmNightsResult
import co.handys.inventory.api.DayQuote
import co.handys.inventory.api.DayQuoteQuery
import co.handys.inventory.api.InventoryQuoteApi
import co.handys.inventory.api.InventorySoldApi
import co.handys.inventory.api.ReleaseNightsCommand
import co.handys.inventory.domain.GateInput
import co.handys.inventory.domain.OverbookGate
import co.handys.inventory.domain.StayNights
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Service
class InventoryQuoteService(
    private val soldRepo: InventorySoldJpaRepository,
    private val holdRepo: InventoryHoldJpaRepository,
) : InventoryQuoteApi {
    @Transactional(readOnly = true)
    override fun quoteDay(query: DayQuoteQuery): DayQuote {
        if (!query.listPricePresent) return emptyQuote(query, ReasonCode.PRICE_MISSING)
        if (!query.inventorySyncFresh) return emptyQuote(query, ReasonCode.INVENTORY_STALE)
        val confirmed = soldCount(query.propertyId, query.roomTypeId, query.date)
        val held = holdRepo.countHeldCoveringNight(query.propertyId, query.roomTypeId, query.date).toInt()
        val occupied = confirmed + held
        val daysUntil = ChronoUnit.DAYS.between(query.today, query.date).toInt()
        val snap = OverbookGate.evaluate(
            GateInput(
                query.mode, query.capacity, daysUntil,
                query.minLeadDays, query.minCapacityForOverbook, query.overbookRate, occupied,
            ),
        )
        return DayQuote(
            query.propertyId, query.roomTypeId, query.date,
            snap.available, occupied, snap.maxSellable, snap.gateOk,
            if (snap.available == 0) snap.rejectHint else null, daysUntil,
        )
    }

    fun soldCount(propertyId: String, roomTypeId: String, date: LocalDate): Int =
        soldRepo.findById(soldId(propertyId, roomTypeId, date)).map { it.sold }.orElse(0)

    private fun emptyQuote(query: DayQuoteQuery, reason: ReasonCode) = DayQuote(
        query.propertyId, query.roomTypeId, query.date, 0,
        soldCount(query.propertyId, query.roomTypeId, query.date),
        0, false, reason, ChronoUnit.DAYS.between(query.today, query.date).toInt(),
    )

    companion object {
        fun soldId(propertyId: String, roomTypeId: String, date: LocalDate) =
            "$propertyId:$roomTypeId:$date"
    }
}

@Service
class InventorySoldStore(
    private val soldRepo: InventorySoldJpaRepository,
    private val quotes: InventoryQuoteService,
) : InventorySoldApi {
    @Transactional
    override fun confirmHotelNights(cmd: ConfirmNightsCommand): ConfirmNightsResult {
        if (!cmd.listPricePresent) return ConfirmNightsResult.Rejected(ReasonCode.PRICE_MISSING, null)
        if (!cmd.inventorySyncFresh) return ConfirmNightsResult.Rejected(ReasonCode.INVENTORY_STALE, null)
        val nights = StayNights.of(cmd.checkIn, cmd.checkOut)
        if (nights.isEmpty()) return ConfirmNightsResult.Rejected(ReasonCode.BAD_REQUEST, null)
        for (night in nights) {
            val q = quotes.quoteDay(
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
            val id = InventoryQuoteService.soldId(cmd.propertyId, cmd.roomTypeId, night)
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
        for (night in StayNights.of(cmd.checkIn, cmd.checkOut)) {
            val id = InventoryQuoteService.soldId(cmd.propertyId, cmd.roomTypeId, night)
            val row = soldRepo.findForUpdate(id) ?: continue
            row.sold = maxOf(0, row.sold - 1)
            soldRepo.save(row)
        }
    }

    @Transactional
    fun seedSold(propertyId: String, roomTypeId: String, date: LocalDate, sold: Int) {
        val id = InventoryQuoteService.soldId(propertyId, roomTypeId, date)
        soldRepo.save(InventorySoldEntity(id, propertyId, roomTypeId, date, sold))
    }
}
