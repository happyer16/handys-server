package co.handys.inventory.infrastructure.redis

import java.time.LocalDate

enum class HotAcquireResult {
    /** Redis preclaim succeeded. */
    OK,
    /** Slot full / NX conflict — reject without hitting PG. */
    FULL,
    /** Redis unavailable or mode degrade — skip Redis, use PG only. */
    SKIP,
}

/**
 * ADR-004 hot layer. Must not be called from inside `@Transactional` methods by callers —
 * orchestration keeps Redis outside DB TX.
 */
interface InventoryHotStore {
    fun ping(): Boolean

    fun tryAcquireUnitNights(
        propertyId: String,
        unitId: String,
        nights: List<LocalDate>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult

    fun tryAcquirePoolNights(
        propertyId: String,
        roomTypeId: String,
        nightCaps: List<Pair<LocalDate, Int>>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult

    fun releaseUnitNights(propertyId: String, unitId: String, nights: List<LocalDate>)

    fun releasePoolNights(propertyId: String, roomTypeId: String, nights: List<LocalDate>)

    fun setHoldMeta(holdId: String, value: String, ttlSeconds: Long)

    fun deleteHoldMeta(holdId: String)

    /** Wipe DB-1 inventory keys and reload from caller-provided SET/INCR ops. */
    fun flushDb()

    fun setUnitNight(propertyId: String, unitId: String, night: LocalDate, holdId: String, ttlSeconds: Long)

    fun incrPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate)

    fun readPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate): Long
}
