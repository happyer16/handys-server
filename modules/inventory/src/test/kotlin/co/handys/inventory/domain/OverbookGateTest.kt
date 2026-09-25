package co.handys.inventory.domain

import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverbookGateTest {
    @Test
    fun `hotel gate ok expands max sellable`() {
        val snap = OverbookGate.evaluate(
            GateInput(SellMode.HOTEL_POOL, 20, 5, 3, 10, 0.10, 20),
        )
        assertTrue(snap.gateOk)
        assertEquals(22, snap.maxSellable)
        assertEquals(2, snap.available)
    }

    @Test
    fun `lead gate fails near checkin`() {
        val snap = OverbookGate.evaluate(
            GateInput(SellMode.HOTEL_POOL, 20, 1, 3, 10, 0.10, 20),
        )
        assertFalse(snap.gateOk)
        assertEquals(ReasonCode.OVERBOOK_GATE_LEAD, snap.rejectHint)
    }

    @Test
    fun `size gate fails for small pool`() {
        val snap = OverbookGate.evaluate(
            GateInput(SellMode.HOTEL_POOL, 5, 10, 3, 10, 0.10, 5),
        )
        assertEquals(ReasonCode.OVERBOOK_GATE_SIZE, snap.rejectHint)
    }

    @Test
    fun `rate zero at capacity`() {
        val snap = OverbookGate.evaluate(
            GateInput(SellMode.HOTEL_POOL, 10, 10, 3, 10, 0.0, 10),
        )
        assertEquals(ReasonCode.AT_CAPACITY, snap.rejectHint)
    }
}
