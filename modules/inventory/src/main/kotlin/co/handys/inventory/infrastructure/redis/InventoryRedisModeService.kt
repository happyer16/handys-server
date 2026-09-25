package co.handys.inventory.infrastructure.redis

import co.handys.inventory.infrastructure.InventoryRedisMode
import co.handys.inventory.infrastructure.InventoryRedisModeEntity
import co.handys.inventory.infrastructure.InventoryRedisModeJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class InventoryRedisModeService(
    private val repo: InventoryRedisModeJpaRepository,
    private val clock: Clock,
    @Value("\${handys.inventory.redis.ping-fail-threshold:3}") private val pingFailThreshold: Int,
) {
    @Transactional
    fun ensureRow() {
        if (!repo.existsById(InventoryRedisModeEntity.SINGLETON_ID)) {
            repo.save(
                InventoryRedisModeEntity(
                    id = InventoryRedisModeEntity.SINGLETON_ID,
                    mode = InventoryRedisMode.NORMAL,
                    updatedAt = clock.instant(),
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    fun current(): InventoryRedisMode =
        repo.findById(InventoryRedisModeEntity.SINGLETON_ID)
            .map { it.mode }
            .orElse(InventoryRedisMode.NORMAL)

    /** True when Redis preclaim is allowed. */
    @Transactional(readOnly = true)
    fun allowsHotPath(): Boolean = current() == InventoryRedisMode.NORMAL

    @Transactional
    fun recordPing(ok: Boolean) {
        ensureRow()
        val row = repo.findById(InventoryRedisModeEntity.SINGLETON_ID).orElseThrow()
        if (row.mode == InventoryRedisMode.REBUILDING) return
        if (ok) {
            row.pingFailCount = 0
            if (row.mode == InventoryRedisMode.DEGRADE) {
                row.mode = InventoryRedisMode.NORMAL
            }
        } else {
            row.pingFailCount += 1
            if (row.pingFailCount >= pingFailThreshold) {
                row.mode = InventoryRedisMode.DEGRADE
            }
        }
        row.updatedAt = clock.instant()
        repo.save(row)
    }

    @Transactional
    fun tryBeginRebuild(instanceId: String): Boolean {
        ensureRow()
        val row = repo.findById(InventoryRedisModeEntity.SINGLETON_ID).orElseThrow()
        if (row.mode == InventoryRedisMode.REBUILDING &&
            row.leaderInstanceId != null &&
            row.leaderInstanceId != instanceId
        ) {
            return false
        }
        row.mode = InventoryRedisMode.REBUILDING
        row.leaderInstanceId = instanceId
        row.updatedAt = clock.instant()
        repo.save(row)
        return true
    }

    @Transactional
    fun finishRebuild(instanceId: String) {
        val row = repo.findById(InventoryRedisModeEntity.SINGLETON_ID).orElseThrow()
        if (row.leaderInstanceId != null && row.leaderInstanceId != instanceId) return
        row.mode = InventoryRedisMode.NORMAL
        row.leaderInstanceId = null
        row.pingFailCount = 0
        row.updatedAt = clock.instant()
        repo.save(row)
    }

    @Transactional
    fun forceDegrade() {
        ensureRow()
        val row = repo.findById(InventoryRedisModeEntity.SINGLETON_ID).orElseThrow()
        row.mode = InventoryRedisMode.DEGRADE
        row.updatedAt = Instant.now(clock)
        repo.save(row)
    }
}
