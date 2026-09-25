package co.handys.inventory.infrastructure.redis

import co.handys.common.domain.SellMode
import co.handys.inventory.infrastructure.InventoryHoldStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/** ADR-004 rebuild / reconcile (app-batch). */
@Service
class InventoryRedisMaintenanceService(
    private val hot: InventoryHotStore,
    private val mode: InventoryRedisModeService,
    private val store: InventoryHoldStore,
    private val clock: Clock,
    @Value("\${handys.inventory.redis.instance-id:batch-1}") private val instanceId: String,
) {
    fun rebuildFromPostgres(): Boolean {
        if (!mode.tryBeginRebuild(instanceId)) return false
        return try {
            hot.flushDb()
            val now = clock.instant()
            for (hold in store.listActiveHeld(now)) {
                val ttlSec = Duration.between(now, hold.expiresAt).seconds.coerceAtLeast(1)
                when (hold.mode) {
                    SellMode.SPECIFIC_UNIT ->
                        hold.nights.forEach { night ->
                            hot.setUnitNight(
                                hold.propertyId,
                                hold.roomTypeOrUnitId,
                                night,
                                hold.holdId,
                                ttlSec,
                            )
                        }
                    SellMode.HOTEL_POOL ->
                        hold.nights.forEach { night ->
                            hot.incrPoolHeld(hold.propertyId, hold.roomTypeOrUnitId, night)
                        }
                }
            }
            mode.finishRebuild(instanceId)
            true
        } catch (ex: Exception) {
            mode.forceDegrade()
            throw ex
        }
    }

    fun reconcile(): Int {
        if (!hot.ping()) {
            mode.recordPing(false)
            return 0
        }
        mode.recordPing(true)
        return if (rebuildFromPostgres()) 1 else 0
    }
}
