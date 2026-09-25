package co.handys.booking.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class PaymentIntentTest : BehaviorSpec({
    Given("a Succeeded payment intent") {
        val intent =
            PaymentIntent.create(
                id = "pi_1",
                reservationId = "r1",
                amountWon = 100_000L,
                idempotencyKey = IdempotencyKeys.chargeFull("r1"),
                expiresAt = Instant.parse("2026-09-25T12:15:00Z"),
                now = Instant.parse("2026-09-25T12:00:00Z"),
            ).markProcessing(Instant.parse("2026-09-25T12:01:00Z"))
                .markSucceeded(Instant.parse("2026-09-25T12:02:00Z"), pgPaymentId = "pg_1")

        When("markSucceeded is called again") {
            Then("it throws because Succeeded is terminal") {
                shouldThrow<IllegalStateException> {
                    intent.markSucceeded(Instant.parse("2026-09-25T12:03:00Z"), pgPaymentId = "pg_1")
                }
            }
        }
    }

    Given("a processing payment intent") {
        val processing =
            PaymentIntent.create(
                id = "pi_2",
                reservationId = "r2",
                amountWon = 100_000L,
                idempotencyKey = IdempotencyKeys.chargeFull("r2"),
                expiresAt = Instant.parse("2026-09-25T12:15:00Z"),
                now = Instant.parse("2026-09-25T12:00:00Z"),
            ).markProcessing(Instant.parse("2026-09-25T12:01:00Z"))

        When("markSucceeded is called with a blank pgPaymentId") {
            Then("it throws IllegalArgumentException") {
                shouldThrow<IllegalArgumentException> {
                    processing.markSucceeded(Instant.parse("2026-09-25T12:02:00Z"), pgPaymentId = " ")
                }
            }
        }
    }

    Given("idempotency key formatting") {
        When("chargeFull is built for a reservation") {
            Then("the key is business-scoped not random") {
                IdempotencyKeys.chargeFull("r1") shouldBe "pay:r1:CHARGE_FULL"
            }
        }
    }
})
