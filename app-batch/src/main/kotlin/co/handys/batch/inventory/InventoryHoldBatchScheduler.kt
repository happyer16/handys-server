package co.handys.batch.inventory

import co.handys.inventory.infrastructure.redis.InventoryHoldService
import co.handys.inventory.infrastructure.redis.InventoryRedisMaintenanceService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** ADR-004: expire held ≤1m SLA + Redis reconcile/rebuild. */
@Component
class InventoryHoldBatchScheduler(
    private val holds: InventoryHoldService,
    private val maintenance: InventoryRedisMaintenanceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${handys.inventory.expire-fixed-delay-ms:30000}")
    fun expireHeld() {
        val n = holds.expireDueHolds()
        if (n > 0) log.info("expired {} inventory holds", n)
    }

    @Scheduled(fixedDelayString = "\${handys.inventory.reconcile-fixed-delay-ms:60000}")
    fun reconcileRedis() {
        val n = maintenance.reconcile()
        if (n > 0) log.info("inventory redis reconcile/rebuild completed")
    }
}
