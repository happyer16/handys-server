package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.ChargeRequest
import co.handys.booking.payment.application.ChargeResult
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf

class MockPaymentGatewayTest : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerTest

    val gateway = MockPaymentGateway(delayMillis = 25)

    afterEach {
        MockPaymentGateway.resetControls()
    }

    Given("default mock gateway behaviour") {
        When("charge is called") {
            val result =
                gateway.charge(
                    ChargeRequest(
                        idempotencyKey = "pay:r1:CHARGE_FULL",
                        amountWon = 50_000,
                        reservationId = "r1",
                    ),
                ).shouldBeInstanceOf<ChargeResult.Succeeded>()

            Then("it succeeds with evt-prefixed pgEventId") {
                result.pgEventId.shouldStartWith("evt_")
                result.pgPaymentId.shouldStartWith("pg_")
            }
        }
    }

    Given("the same idempotency key") {
        val request =
            ChargeRequest(
                idempotencyKey = "pay:r1:CHARGE_FULL",
                amountWon = 50_000,
                reservationId = "r1",
            )

        When("charge is called twice") {
            val first = gateway.charge(request).shouldBeInstanceOf<ChargeResult.Succeeded>()
            val second = gateway.charge(request).shouldBeInstanceOf<ChargeResult.Succeeded>()

            Then("pg ids stay stable") {
                first.pgPaymentId shouldBe second.pgPaymentId
                first.pgEventId shouldBe second.pgEventId
            }
        }
    }

    Given("different idempotency keys") {
        When("charge is called for each") {
            val a =
                gateway.charge(ChargeRequest("pay:r1:CHARGE_FULL", 1, "r1"))
                    .shouldBeInstanceOf<ChargeResult.Succeeded>()
            val b =
                gateway.charge(ChargeRequest("pay:r2:CHARGE_FULL", 1, "r2"))
                    .shouldBeInstanceOf<ChargeResult.Succeeded>()

            Then("pg ids differ") {
                a.pgPaymentId shouldNotBe b.pgPaymentId
            }
        }
    }

    Given("nextBehavior DECLINE") {
        MockPaymentGateway.nextBehavior = MockChargeBehavior.DECLINE

        When("charge is called") {
            val result = gateway.charge(ChargeRequest("pay:r1:CHARGE_FULL", 1, "r1"))

            Then("it returns declined") {
                result.shouldBeInstanceOf<ChargeResult.Declined>()
            }
        }
    }

    Given("nextBehavior DELAY") {
        MockPaymentGateway.nextBehavior = MockChargeBehavior.DELAY

        When("charge is called") {
            val started = System.nanoTime()
            val result =
                gateway.charge(ChargeRequest("pay:r1:CHARGE_FULL", 1, "r1"))
                    .shouldBeInstanceOf<ChargeResult.Succeeded>()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            Then("it still succeeds after waiting") {
                (elapsedMs >= 20) shouldBe true
                result.pgEventId.shouldStartWith("evt_")
            }
        }
    }
})
