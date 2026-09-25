package co.handys.inventory.infrastructure

import co.handys.common.domain.SellMode
import co.handys.inventory.api.HoldCommand
import co.handys.inventory.api.HoldResult
import co.handys.inventory.domain.GateInput
import co.handys.inventory.domain.OverbookGate
import co.handys.inventory.domain.StayNights
import co.handys.property.api.PropertyApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Postgres SSOT for holds (ADR-004). Called only from [co.handys.inventory.infrastructure.redis.InventoryHoldService]
 * after optional Redis preclaim. Redis must not be invoked here.
 */
@Service
class InventoryHoldStore(
    private val holdRepo: InventoryHoldJpaRepository,
    private val soldRepo: InventorySoldJpaRepository,
    private val unitNightRepo: InventoryUnitNightJpaRepository,
    private val propertyApi: PropertyApi,
    private val clock: Clock,
) {
    data class HoldSnapshot(
        val holdId: String,
        val propertyId: String,
        val roomTypeOrUnitId: String,
        val checkIn: LocalDate,
        val checkOut: LocalDate,
        val mode: SellMode,
        val nights: List<LocalDate>,
        val status: String,
    )

    @Transactional
    fun hold(cmd: HoldCommand, holdId: String): HoldResult {
        val nights = StayNights.of(cmd.checkIn, cmd.checkOut)
        if (nights.isEmpty()) throw IllegalArgumentException("empty stay nights")

        when (cmd.mode) {
            SellMode.SPECIFIC_UNIT -> insertUnitNights(cmd, holdId, nights)
            SellMode.HOTEL_POOL -> assertPoolCapacity(cmd, nights)
        }

        val slot = slotKey(cmd)
        if (holdRepo.findBySlotKey(slot) != null) {
            throw IllegalStateException("inventory slot already held")
        }
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
    fun confirmHold(holdId: String): HoldSnapshot {
        val hold = holdRepo.findForUpdate(holdId)
            ?: throw IllegalArgumentException("unknown holdId: $holdId")
        when (hold.status) {
            "CONFIRMED" -> return snapshot(hold)
            "RELEASED" -> throw IllegalStateException("hold already released: $holdId")
        }
        val nights = StayNights.of(hold.checkIn, hold.checkOut)
        if (hold.mode == SellMode.HOTEL_POOL) {
            for (night in nights) {
                val id = soldId(hold.propertyId, hold.roomTypeOrUnitId, night)
                var row = soldRepo.findForUpdate(id)
                if (row == null) {
                    soldRepo.save(
                        InventorySoldEntity(id, hold.propertyId, hold.roomTypeOrUnitId, night, 0),
                    )
                    row = soldRepo.findForUpdate(id)!!
                }
                row.sold += 1
                soldRepo.save(row)
            }
        } else {
            unitNightRepo.findAllByHoldId(holdId).forEach { night ->
                night.status = "CONFIRMED"
                unitNightRepo.save(night)
            }
        }
        hold.status = "CONFIRMED"
        holdRepo.save(hold)
        return snapshot(hold)
    }

    @Transactional
    fun releaseHold(holdId: String): HoldSnapshot? {
        val hold = holdRepo.findForUpdate(holdId)
            ?: throw IllegalArgumentException("unknown holdId: $holdId")
        if (hold.status == "RELEASED") return null
        val snap = snapshot(hold)
        if (hold.mode == SellMode.SPECIFIC_UNIT && hold.status != "CONFIRMED") {
            unitNightRepo.deleteByHoldId(holdId)
        }
        hold.status = "RELEASED"
        holdRepo.save(hold)
        holdRepo.delete(hold)
        return snap
    }

    @Transactional(readOnly = true)
    fun getSnapshot(holdId: String): HoldSnapshot? =
        holdRepo.findById(holdId).map { snapshot(it) }.orElse(null)

    @Transactional(readOnly = true)
    fun listExpiredHeld(now: java.time.Instant): List<HoldSnapshot> =
        holdRepo.findExpiredHeld(now).map { snapshot(it) }

    @Transactional(readOnly = true)
    fun listActiveHeld(now: java.time.Instant): List<HoldSnapshot> =
        holdRepo.findActiveHeld(now).map { snapshot(it) }

    fun maxSellableForNight(cmd: HoldCommand, night: LocalDate, sold: Int): Int {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, night).toInt()
        return when (cmd.mode) {
            SellMode.SPECIFIC_UNIT -> 1
            SellMode.HOTEL_POOL -> {
                val rt = propertyApi.getRoomType(cmd.roomTypeOrUnitId)
                    ?: throw IllegalArgumentException("unknown roomType: ${cmd.roomTypeOrUnitId}")
                OverbookGate.evaluate(
                    GateInput(
                        mode = cmd.mode,
                        capacity = rt.capacity,
                        daysUntil = daysUntil,
                        minLeadDays = rt.minLeadDays,
                        minCapacityForOverbook = rt.minCapacityForOverbook,
                        overbookRate = rt.overbookRate,
                        sold = sold,
                    ),
                ).maxSellable
            }
        }
    }

    fun approxCapRemaining(cmd: HoldCommand, night: LocalDate): Int {
        val sold = soldRepo.findById(soldId(cmd.propertyId, cmd.roomTypeOrUnitId, night))
            .map { it.sold }.orElse(0)
        val held = holdRepo.countHeldCoveringNight(cmd.propertyId, cmd.roomTypeOrUnitId, night).toInt()
        return maxOf(0, maxSellableForNight(cmd, night, sold) - sold - held)
    }

    private fun insertUnitNights(cmd: HoldCommand, holdId: String, nights: List<LocalDate>) {
        for (night in nights) {
            val id = InventoryUnitNightEntity.idOf(cmd.propertyId, cmd.roomTypeOrUnitId, night)
            if (unitNightRepo.existsById(id)) {
                throw IllegalStateException("unit night already occupied: $id")
            }
            unitNightRepo.save(
                InventoryUnitNightEntity(
                    id = id,
                    propertyId = cmd.propertyId,
                    unitId = cmd.roomTypeOrUnitId,
                    stayDate = night,
                    holdId = holdId,
                    status = "HELD",
                ),
            )
        }
    }

    private fun assertPoolCapacity(cmd: HoldCommand, nights: List<LocalDate>) {
        val rt = propertyApi.getRoomType(cmd.roomTypeOrUnitId)
            ?: throw IllegalArgumentException("unknown roomType: ${cmd.roomTypeOrUnitId}")
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        for (night in nights) {
            val soldId = soldId(cmd.propertyId, cmd.roomTypeOrUnitId, night)
            var row = soldRepo.findForUpdate(soldId)
            if (row == null) {
                soldRepo.save(
                    InventorySoldEntity(soldId, cmd.propertyId, cmd.roomTypeOrUnitId, night, 0),
                )
                row = soldRepo.findForUpdate(soldId)!!
            }
            val heldCnt = holdRepo.countHeldCoveringNight(
                cmd.propertyId,
                cmd.roomTypeOrUnitId,
                night,
            ).toInt()
            val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, night).toInt()
            val maxSellable = OverbookGate.evaluate(
                GateInput(
                    mode = cmd.mode,
                    capacity = rt.capacity,
                    daysUntil = daysUntil,
                    minLeadDays = rt.minLeadDays,
                    minCapacityForOverbook = rt.minCapacityForOverbook,
                    overbookRate = rt.overbookRate,
                    sold = row.sold,
                ),
            ).maxSellable
            if (heldCnt + row.sold + 1 > maxSellable) {
                throw IllegalStateException("pool at capacity for $night")
            }
        }
    }

    private fun snapshot(hold: InventoryHoldEntity) = HoldSnapshot(
        holdId = hold.holdId,
        propertyId = hold.propertyId,
        roomTypeOrUnitId = hold.roomTypeOrUnitId,
        checkIn = hold.checkIn,
        checkOut = hold.checkOut,
        mode = hold.mode,
        nights = StayNights.of(hold.checkIn, hold.checkOut),
        status = hold.status,
    )

    companion object {
        fun slotKey(cmd: HoldCommand) =
            "${cmd.propertyId}:${cmd.roomTypeOrUnitId}:${cmd.checkIn}:${cmd.checkOut}:${cmd.mode}"

        fun soldId(propertyId: String, roomTypeId: String, date: LocalDate) =
            "$propertyId:$roomTypeId:$date"
    }
}
