package co.handys.inventory.infrastructure.redis

import co.handys.common.domain.SellMode
import co.handys.inventory.api.HoldCommand
import co.handys.inventory.api.HoldResult
import co.handys.inventory.api.InventoryHoldApi
import co.handys.inventory.domain.StayNights
import co.handys.inventory.infrastructure.InventoryHoldStore
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-004 hold orchestration: Redis preclaim (Non-TX) → PG TX → after-commit / rollback hooks.
 */
@Service
class InventoryHoldService(
    private val hot: InventoryHotStore,
    private val mode: InventoryRedisModeService,
    private val store: InventoryHoldStore,
    private val clock: Clock,
) : InventoryHoldApi {
    override fun hold(cmd: HoldCommand): HoldResult {
        val holdId = UUID.randomUUID().toString()
        val nights = StayNights.of(cmd.checkIn, cmd.checkOut)
        if (nights.isEmpty()) throw IllegalArgumentException("empty stay nights")
        val ttlSeconds = Duration.between(clock.instant(), cmd.expiresAt).seconds.coerceAtLeast(1)

        var redisAcquired = false
        if (mode.allowsHotPath()) {
            val pingOk = hot.ping()
            mode.recordPing(pingOk)
            if (pingOk && mode.allowsHotPath()) {
                val result = when (cmd.mode) {
                    SellMode.SPECIFIC_UNIT ->
                        hot.tryAcquireUnitNights(
                            cmd.propertyId,
                            cmd.roomTypeOrUnitId,
                            nights,
                            holdId,
                            ttlSeconds,
                        )
                    SellMode.HOTEL_POOL -> {
                        val caps = nights.map { night -> night to store.approxCapRemaining(cmd, night) }
                        if (caps.any { it.second <= 0 }) {
                            HotAcquireResult.FULL
                        } else {
                            hot.tryAcquirePoolNights(
                                cmd.propertyId,
                                cmd.roomTypeOrUnitId,
                                caps,
                                holdId,
                                ttlSeconds,
                            )
                        }
                    }
                }
                when (result) {
                    HotAcquireResult.FULL -> throw IllegalStateException("inventory slot already held")
                    HotAcquireResult.OK -> redisAcquired = true
                    HotAcquireResult.SKIP -> Unit
                }
            }
        }

        return try {
            store.hold(cmd, holdId) {
                if (redisAcquired) {
                    releaseHot(cmd.mode, cmd.propertyId, cmd.roomTypeOrUnitId, nights, holdId)
                }
            }
        } catch (ex: RuntimeException) {
            if (redisAcquired) {
                releaseHot(cmd.mode, cmd.propertyId, cmd.roomTypeOrUnitId, nights, holdId)
            }
            throw ex
        }
    }

    override fun confirmHold(holdId: String) {
        store.confirmHold(holdId) { snap ->
            releaseHot(snap.mode, snap.propertyId, snap.roomTypeOrUnitId, snap.nights, holdId)
        }
    }

    override fun releaseHold(holdId: String) {
        store.releaseHold(holdId) { snap ->
            releaseHot(snap.mode, snap.propertyId, snap.roomTypeOrUnitId, snap.nights, holdId)
        }
    }

    fun expireDueHolds(): Int {
        var n = 0
        for (snap in store.listExpiredHeld(clock.instant())) {
            try {
                releaseHold(snap.holdId)
                n++
            } catch (_: Exception) {
                // retry next tick
            }
        }
        return n
    }

    private fun releaseHot(
        sellMode: SellMode,
        propertyId: String,
        roomTypeOrUnitId: String,
        nights: List<LocalDate>,
        holdId: String,
    ) {
        when (sellMode) {
            SellMode.SPECIFIC_UNIT -> hot.releaseUnitNights(propertyId, roomTypeOrUnitId, nights)
            SellMode.HOTEL_POOL -> hot.releasePoolNights(propertyId, roomTypeOrUnitId, nights)
        }
        hot.deleteHoldMeta(holdId)
    }
}
