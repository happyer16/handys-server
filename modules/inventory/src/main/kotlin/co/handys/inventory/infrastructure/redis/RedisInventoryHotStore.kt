package co.handys.inventory.infrastructure.redis

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate

@Component
@ConditionalOnProperty(name = ["handys.inventory.redis.enabled"], havingValue = "true")
class RedisInventoryHotStore(
    private val redis: StringRedisTemplate,
) : InventoryHotStore {
    private val incrCapScript =
        DefaultRedisScript<String>().apply {
            setScriptText(
                """
                local v = redis.call('INCR', KEYS[1])
                if v > tonumber(ARGV[1]) then
                  redis.call('DECR', KEYS[1])
                  return 'FULL'
                end
                return 'OK'
                """.trimIndent(),
            )
            resultType = String::class.java
        }

    override fun ping(): Boolean =
        try {
            val pong = redis.connectionFactory?.connection?.use { it.ping() }
            pong != null
        } catch (_: Exception) {
            false
        }

    override fun tryAcquireUnitNights(
        propertyId: String,
        unitId: String,
        nights: List<LocalDate>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult {
        val acquired = mutableListOf<String>()
        return try {
            for (night in nights) {
                val key = InventoryRedisKeys.unitNight(propertyId, unitId, night)
                val ok = redis.opsForValue().setIfAbsent(key, holdId, Duration.ofSeconds(ttlSeconds))
                if (ok != true) {
                    releaseKeys(acquired)
                    return HotAcquireResult.FULL
                }
                acquired += key
            }
            HotAcquireResult.OK
        } catch (_: Exception) {
            releaseKeys(acquired)
            HotAcquireResult.SKIP
        }
    }

    override fun tryAcquirePoolNights(
        propertyId: String,
        roomTypeId: String,
        nightCaps: List<Pair<LocalDate, Int>>,
        holdId: String,
        ttlSeconds: Long,
    ): HotAcquireResult {
        val acquired = mutableListOf<LocalDate>()
        return try {
            for ((night, cap) in nightCaps) {
                val key = InventoryRedisKeys.poolHeld(propertyId, roomTypeId, night)
                val result = redis.execute(incrCapScript, listOf(key), cap.toString())
                if (result != "OK") {
                    releasePoolNights(propertyId, roomTypeId, acquired)
                    return HotAcquireResult.FULL
                }
                acquired += night
            }
            setHoldMeta(holdId, holdId, ttlSeconds)
            HotAcquireResult.OK
        } catch (_: Exception) {
            releasePoolNights(propertyId, roomTypeId, acquired)
            HotAcquireResult.SKIP
        }
    }

    override fun releaseUnitNights(propertyId: String, unitId: String, nights: List<LocalDate>) {
        if (nights.isEmpty()) return
        val keys = nights.map { InventoryRedisKeys.unitNight(propertyId, unitId, it) }
        try {
            redis.delete(keys)
        } catch (_: Exception) {
            // reconcile batch recovers
        }
    }

    override fun releasePoolNights(propertyId: String, roomTypeId: String, nights: List<LocalDate>) {
        for (night in nights) {
            try {
                val key = InventoryRedisKeys.poolHeld(propertyId, roomTypeId, night)
                val v = redis.opsForValue().decrement(key) ?: 0
                if (v <= 0) redis.delete(key)
            } catch (_: Exception) {
                // reconcile
            }
        }
    }

    override fun setHoldMeta(holdId: String, value: String, ttlSeconds: Long) {
        try {
            redis.opsForValue().set(
                InventoryRedisKeys.holdMeta(holdId),
                value,
                Duration.ofSeconds(ttlSeconds.coerceAtLeast(1)),
            )
        } catch (_: Exception) {
            // optional meta
        }
    }

    override fun deleteHoldMeta(holdId: String) {
        try {
            redis.delete(InventoryRedisKeys.holdMeta(holdId))
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun flushDb() {
        redis.connectionFactory?.connection?.use { it.serverCommands().flushDb() }
    }

    override fun setUnitNight(propertyId: String, unitId: String, night: LocalDate, holdId: String, ttlSeconds: Long) {
        redis.opsForValue().set(
            InventoryRedisKeys.unitNight(propertyId, unitId, night),
            holdId,
            Duration.ofSeconds(ttlSeconds.coerceAtLeast(1)),
        )
    }

    override fun incrPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate) {
        redis.opsForValue().increment(InventoryRedisKeys.poolHeld(propertyId, roomTypeId, night))
    }

    override fun readPoolHeld(propertyId: String, roomTypeId: String, night: LocalDate): Long =
        redis.opsForValue().get(InventoryRedisKeys.poolHeld(propertyId, roomTypeId, night))?.toLongOrNull() ?: 0

    private fun releaseKeys(keys: List<String>) {
        if (keys.isNotEmpty()) redis.delete(keys)
    }
}
