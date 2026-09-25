package co.handys.inventory.infrastructure.redis

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process hot store for tests and when Redis is disabled.
 * Always reports [HotAcquireResult.SKIP] from [ping]=false so orchestration uses PG-only (DEGRADE path).
 * Use [InMemoryInventoryHotStore.Active] when tests need NX/INCR semantics without Redis.
 */
@Component
@ConditionalOnProperty(name = ["handys.inventory.redis.enabled"], havingValue = "false", matchIfMissing = true)
class NoOpInventoryHotStore : InventoryHotStore {
    override fun ping(): Boolean = false

    override fun tryAcquireUnitNights(
        propertyId: String,
        unitId: String,
        nights: List<LocalDate>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult = HotAcquireResult.SKIP

    override fun tryAcquirePoolNights(
        propertyId: String,
        roomTypeId: String,
        nightCaps: List<Pair<LocalDate, Int>>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult = HotAcquireResult.SKIP

    override fun releaseUnitNights(propertyId: String, unitId: String, nights: List<LocalDate>) = Unit

    override fun releasePoolNights(propertyId: String, roomTypeId: String, nights: List<LocalDate>) = Unit

    override fun setHoldMeta(holdId: String, value: String, ttlSeconds: Long) = Unit

    override fun deleteHoldMeta(holdId: String) = Unit

    override fun flushDb() = Unit

    override fun setUnitNight(propertyId: String, unitId: String, night: LocalDate, holdId: String, ttlSeconds: Long) = Unit

    override fun incrPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate) = Unit

    override fun readPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate): Long = 0
}

/** Test double with real NX/INCR semantics (no TTL expiry simulation). */
class InMemoryInventoryHotStore : InventoryHotStore {
    private val unit = ConcurrentHashMap<String, String>()
    private val pool = ConcurrentHashMap<String, AtomicLong>()
    private val meta = ConcurrentHashMap<String, String>()
    @Volatile var available: Boolean = true

    override fun ping(): Boolean = available

    override fun tryAcquireUnitNights(
        propertyId: String,
        unitId: String,
        nights: List<LocalDate>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult {
        if (!available) return HotAcquireResult.SKIP
        val acquired = mutableListOf<String>()
        for (night in nights) {
            val key = InventoryRedisKeys.unitNight(propertyId, unitId, night)
            val prev = unit.putIfAbsent(key, holdId)
            if (prev != null) {
                acquired.forEach { unit.remove(it, holdId) }
                return HotAcquireResult.FULL
            }
            acquired += key
        }
        return HotAcquireResult.OK
    }

    override fun tryAcquirePoolNights(
        propertyId: String,
        roomTypeId: String,
        nightCaps: List<Pair<LocalDate, Int>>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult {
        if (!available) return HotAcquireResult.SKIP
        val acquired = mutableListOf<String>()
        for ((night, cap) in nightCaps) {
            val key = InventoryRedisKeys.poolHeld(propertyId, roomTypeId, night)
            val counter = pool.computeIfAbsent(key) { AtomicLong(0) }
            val v = counter.incrementAndGet()
            if (v > cap) {
                counter.decrementAndGet()
                acquired.forEach { pool[it]?.decrementAndGet() }
                return HotAcquireResult.FULL
            }
            acquired += key
        }
        meta[InventoryRedisKeys.holdMeta(holdId)] = holdId
        return HotAcquireResult.OK
    }

    override fun releaseUnitNights(propertyId: String, unitId: String, nights: List<LocalDate>) {
        nights.forEach { unit.remove(InventoryRedisKeys.unitNight(propertyId, unitId, it)) }
    }

    override fun releasePoolNights(propertyId: String, roomTypeId: String, nights: List<LocalDate>) {
        nights.forEach {
            val key = InventoryRedisKeys.poolHeld(propertyId, roomTypeId, it)
            pool[key]?.updateAndGet { cur -> maxOf(0, cur - 1) }
        }
    }

    override fun setHoldMeta(holdId: String, value: String, ttlSeconds: Long) {
        meta[InventoryRedisKeys.holdMeta(holdId)] = value
    }

    override fun deleteHoldMeta(holdId: String) {
        meta.remove(InventoryRedisKeys.holdMeta(holdId))
    }

    override fun flushDb() {
        unit.clear()
        pool.clear()
        meta.clear()
    }

    override fun setUnitNight(propertyId: String, unitId: String, night: LocalDate, holdId: String, ttlSeconds: Long) {
        unit[InventoryRedisKeys.unitNight(propertyId, unitId, night)] = holdId
    }

    override fun incrPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate) {
        val key = InventoryRedisKeys.poolHeld(propertyId, roomTypeId, night)
        pool.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    override fun readPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate): Long =
        pool[InventoryRedisKeys.poolHeld(propertyId, roomTypeId, night)]?.get() ?: 0
}
