package co.handys.booking.payment.application

import co.handys.booking.payment.fake.FakeIdempotencyStore
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class IdempotencyStoreTest : BehaviorSpec({
    Given("an empty idempotency store") {
        val store = FakeIdempotencyStore()

        When("begin is called twice for the same key") {
            val a = store.begin("pay:r1:CHARGE_FULL")
            val b = store.begin("pay:r1:CHARGE_FULL")

            Then("the second begin returns the first in-flight entry") {
                a.shouldBeInstanceOf<BeginResult.Acquired>()
                val existing = b.shouldBeInstanceOf<BeginResult.Existing>()
                existing.entry.key shouldBe "pay:r1:CHARGE_FULL"
                existing.entry.payload.shouldBeNull()
                existing.entry.terminal shouldBe false
                existing.entry.inFlight shouldBe true
            }
        }
    }

    Given("a terminal completed key") {
        val store = FakeIdempotencyStore()
        store.begin("pay:r1:CHARGE_FULL").shouldBeInstanceOf<BeginResult.Acquired>()
        store.complete("pay:r1:CHARGE_FULL", responsePayload = """{"pgPaymentId":"pg_1"}""", terminal = true)

        When("begin is called again") {
            val again = store.begin("pay:r1:CHARGE_FULL").shouldBeInstanceOf<BeginResult.Existing>()

            Then("the stored payload is returned") {
                again.entry.terminal shouldBe true
                again.entry.payload shouldBe """{"pgPaymentId":"pg_1"}"""
            }
        }
    }

    Given("a non-terminal completed key") {
        val store = FakeIdempotencyStore()
        store.begin("pay:r1:CHARGE_FULL").shouldBeInstanceOf<BeginResult.Acquired>()
        store.complete("pay:r1:CHARGE_FULL", responsePayload = """{"reason":"declined"}""", terminal = false)

        When("begin is called again") {
            val reacquired = store.begin("pay:r1:CHARGE_FULL")
            val concurrent = store.begin("pay:r1:CHARGE_FULL")

            Then("the key is re-acquired and concurrent callers are held off") {
                reacquired.shouldBeInstanceOf<BeginResult.Acquired>()
                val existing = concurrent.shouldBeInstanceOf<BeginResult.Existing>()
                existing.entry.inFlight shouldBe true
                existing.entry.payload.shouldBeNull()
            }
        }
    }
})
