package co.handys.inventory

import co.handys.common.domain.SellMode
import co.handys.inventory.api.HoldCommand
import co.handys.inventory.fake.FakeInventoryService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant
import java.time.LocalDate

class InventoryHoldTest {
    @Test
    fun `confirmHold is idempotent`() {
        val api = FakeInventoryService()
        val hold = api.hold(sampleHold())
        api.confirmHold(hold.holdId)
        api.confirmHold(hold.holdId)
    }

    @Test
    fun `confirmHold on unknown holdId throws`() {
        val api = FakeInventoryService()
        assertFailsWith<IllegalArgumentException> {
            api.confirmHold("missing")
        }
    }

    @Test
    fun `confirmHold after release throws`() {
        val api = FakeInventoryService()
        val hold = api.hold(sampleHold())
        api.releaseHold(hold.holdId)
        assertFailsWith<IllegalStateException> {
            api.confirmHold(hold.holdId)
        }
    }

    @Test
    fun `hold returns holdId and expiresAt from command`() {
        val api = FakeInventoryService()
        val expiresAt = Instant.parse("2026-09-25T12:15:00Z")
        val hold = api.hold(sampleHold(expiresAt = expiresAt))
        assertEquals(expiresAt, hold.expiresAt)
    }

    private fun sampleHold(expiresAt: Instant = Instant.parse("2026-09-25T12:15:00Z")) =
        HoldCommand(
            propertyId = "p1",
            roomTypeOrUnitId = "rt1",
            checkIn = LocalDate.parse("2026-10-01"),
            checkOut = LocalDate.parse("2026-10-03"),
            mode = SellMode.HOTEL_POOL,
            expiresAt = expiresAt,
        )
}
