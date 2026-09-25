package co.handys.inventory

import co.handys.common.domain.SellMode
import co.handys.inventory.api.HoldCommand
import co.handys.inventory.fake.FakeInventoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate

class InventoryHoldTest : BehaviorSpec({
    Given("a confirmed hold") {
        val api = FakeInventoryService()
        val hold = api.hold(sampleHold())
        api.confirmHold(hold.holdId)

        When("confirmHold is called again") {
            Then("it is idempotent") {
                api.confirmHold(hold.holdId)
            }
        }
    }

    Given("an unknown hold id") {
        val api = FakeInventoryService()

        When("confirmHold is called") {
            Then("it throws IllegalArgumentException") {
                shouldThrow<IllegalArgumentException> {
                    api.confirmHold("missing")
                }
            }
        }
    }

    Given("a released hold") {
        val api = FakeInventoryService()
        val hold = api.hold(sampleHold())
        api.releaseHold(hold.holdId)

        When("confirmHold is called") {
            Then("it throws IllegalStateException") {
                shouldThrow<IllegalStateException> {
                    api.confirmHold(hold.holdId)
                }
            }
        }
    }

    Given("a hold command with an expiresAt") {
        val api = FakeInventoryService()
        val expiresAt = Instant.parse("2026-09-25T12:15:00Z")

        When("hold is created") {
            val hold = api.hold(sampleHold(expiresAt = expiresAt))

            Then("holdId and expiresAt come from the command") {
                hold.expiresAt shouldBe expiresAt
            }
        }
    }
})

private fun sampleHold(expiresAt: Instant = Instant.parse("2026-09-25T12:15:00Z")) =
    HoldCommand(
        propertyId = "p1",
        roomTypeOrUnitId = "rt1",
        checkIn = LocalDate.parse("2026-10-01"),
        checkOut = LocalDate.parse("2026-10-03"),
        mode = SellMode.HOTEL_POOL,
        expiresAt = expiresAt,
    )
