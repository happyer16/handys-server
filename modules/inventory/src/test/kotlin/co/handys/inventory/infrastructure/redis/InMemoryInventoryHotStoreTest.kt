package co.handys.inventory.infrastructure.redis

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryInventoryHotStoreTest {
    @Test
    fun `unit NX rejects overlapping night`() {
        val hot = InMemoryInventoryHotStore()
        val nights = listOf(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"))
        assertEquals(
            HotAcquireResult.OK,
            hot.tryAcquireUnitNights("p", "u1", nights, "h1", 900),
        )
        assertEquals(
            HotAcquireResult.FULL,
            hot.tryAcquireUnitNights("p", "u1", listOf(LocalDate.parse("2026-10-02")), "h2", 900),
        )
    }

    @Test
    fun `pool Lua-equivalent respects cap`() {
        val hot = InMemoryInventoryHotStore()
        val night = LocalDate.parse("2026-10-01")
        assertEquals(
            HotAcquireResult.OK,
            hot.tryAcquirePoolNights("p", "rt", listOf(night to 2), "h1", 900),
        )
        assertEquals(
            HotAcquireResult.OK,
            hot.tryAcquirePoolNights("p", "rt", listOf(night to 2), "h2", 900),
        )
        assertEquals(
            HotAcquireResult.FULL,
            hot.tryAcquirePoolNights("p", "rt", listOf(night to 2), "h3", 900),
        )
        assertEquals(2, hot.readPoolHeld("p", "rt", night))
    }

    @Test
    fun `unavailable redis skips`() {
        val hot = InMemoryInventoryHotStore().also { it.available = false }
        assertEquals(
            HotAcquireResult.SKIP,
            hot.tryAcquireUnitNights("p", "u", listOf(LocalDate.parse("2026-10-01")), "h", 60),
        )
        assertTrue(!hot.ping())
    }
}

class InventoryRedisKeysTest {
    @Test
    fun `key shapes match ADR-004`() {
        val d = LocalDate.parse("2026-10-01")
        assertEquals("inv:hold:unit:p:u:$d", InventoryRedisKeys.unitNight("p", "u", d))
        assertEquals("inv:held:pool:p:rt:$d", InventoryRedisKeys.poolHeld("p", "rt", d))
        assertEquals("inv:hold:meta:h1", InventoryRedisKeys.holdMeta("h1"))
    }
}
