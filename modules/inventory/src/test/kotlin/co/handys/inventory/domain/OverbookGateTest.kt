package co.handys.inventory.domain

import co.handys.common.domain.ReasonCode
import co.handys.common.domain.SellMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class OverbookGateTest : BehaviorSpec({
    Given("a hotel pool with healthy lead and size") {
        When("the overbook gate is evaluated") {
            val snap =
                OverbookGate.evaluate(
                    GateInput(SellMode.HOTEL_POOL, 20, 5, 3, 10, 0.10, 20),
                )

            Then("the gate expands max sellable") {
                snap.gateOk shouldBe true
                snap.maxSellable shouldBe 22
                snap.available shouldBe 2
            }
        }
    }

    Given("a hotel pool near check-in") {
        When("the overbook gate is evaluated") {
            val snap =
                OverbookGate.evaluate(
                    GateInput(SellMode.HOTEL_POOL, 20, 1, 3, 10, 0.10, 20),
                )

            Then("the lead gate fails") {
                snap.gateOk shouldBe false
                snap.rejectHint shouldBe ReasonCode.OVERBOOK_GATE_LEAD
            }
        }
    }

    Given("a small hotel pool") {
        When("the overbook gate is evaluated") {
            val snap =
                OverbookGate.evaluate(
                    GateInput(SellMode.HOTEL_POOL, 5, 10, 3, 10, 0.10, 5),
                )

            Then("the size gate fails") {
                snap.rejectHint shouldBe ReasonCode.OVERBOOK_GATE_SIZE
            }
        }
    }

    Given("a hotel pool at capacity with zero overbook rate") {
        When("the overbook gate is evaluated") {
            val snap =
                OverbookGate.evaluate(
                    GateInput(SellMode.HOTEL_POOL, 10, 10, 3, 10, 0.0, 10),
                )

            Then("it reports at capacity") {
                snap.rejectHint shouldBe ReasonCode.AT_CAPACITY
            }
        }
    }
})
