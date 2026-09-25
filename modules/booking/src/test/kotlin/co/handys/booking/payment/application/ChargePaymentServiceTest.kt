package co.handys.booking.payment.application

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.domain.IdempotencyKeys
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.fake.FakeIdempotencyStore
import co.handys.booking.payment.fake.FakeInventoryService
import co.handys.booking.payment.fake.FakePaymentIntentRepository
import co.handys.booking.payment.fake.FakeReservationRepository
import co.handys.booking.payment.support.NoOpTransactionManager
import co.handys.booking.payment.support.TransactionAssertingGateway
import co.handys.common.domain.SellMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ChargePaymentServiceTest : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerTest

    var now: Instant = Instant.parse("2026-09-25T12:00:00Z")

    val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    val inventory = FakeInventoryService()
    val reservations = FakeReservationRepository()
    val paymentIntents = FakePaymentIntentRepository()
    val idempotency = FakeIdempotencyStore()
    val transactions = TransactionTemplate(NoOpTransactionManager())

    var gatewayBehaviour: (ChargeRequest) -> ChargeResult = {
        ChargeResult.Succeeded(pgPaymentId = "pg_test_1", pgEventId = "evt_test_1")
    }
    val gateway = TransactionAssertingGateway(behaviour = { gatewayBehaviour(it) })

    val service =
        ChargePaymentService(
            transactions = transactions,
            gateway = gateway,
            reservations = reservations,
            paymentIntents = paymentIntents,
            idempotency = idempotency,
            inventory = inventory,
            clock = clock,
        )

    val prepareService =
        CreateDirectReservationService(
            inventory = inventory,
            reservations = reservations,
            paymentIntents = paymentIntents,
            clock = clock,
        )

    fun prepareReservation(): String =
        prepareService
            .execute(
                CreateDirectReservationCommand(
                    propertyId = "property-1",
                    roomTypeOrUnitId = "deluxe",
                    checkIn = LocalDate.of(2026, 10, 1),
                    checkOut = LocalDate.of(2026, 10, 3),
                    amountWon = 120_000L,
                    mode = SellMode.HOTEL_POOL,
                ),
            ).reservationId

    fun succeeded() = ChargeResult.Succeeded(pgPaymentId = "pg_test_1", pgEventId = "evt_test_1")

    Given("a TransactionTemplate") {
        When("execute runs a callback") {
            Then("the template really activates a transaction") {
                TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
                val insideTransaction =
                    transactions.execute { TransactionSynchronizationManager.isActualTransactionActive() }
                insideTransaction shouldBe true
                TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
            }
        }
    }

    Given("a prepared reservation") {
        val reservationId = prepareReservation()

        When("charge is called five times") {
            val results = (1..5).map { service.charge(reservationId) }

            Then("the gateway is invoked only once and later calls replay") {
                gateway.chargeCount shouldBe 1
                val first = results.first().shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()
                results.drop(1).forEach {
                    val replay = it.shouldBeInstanceOf<ChargePaymentResult.AlreadySucceeded>()
                    replay.pgPaymentId shouldBe first.pgPaymentId
                }
            }
        }
    }

    Given("a prepared reservation for finalize") {
        val reservationId = prepareReservation()
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId

        When("a succeeded charge runs") {
            val result = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()

            Then("inventory and reservation confirm in one finalize step") {
                inventory.isConfirmed(holdId) shouldBe true
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.CONFIRMED

                val intent =
                    paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)).shouldNotBeNull()
                intent.status shouldBe PaymentIntentStatus.Succeeded
                intent.pgPaymentId shouldBe result.pgPaymentId

                val record =
                    idempotency.begin(IdempotencyKeys.chargeFull(reservationId))
                        .shouldBeInstanceOf<BeginResult.Existing>()
                record.entry.terminal shouldBe true
                record.entry.payload.shouldNotBeNull().shouldContain(result.pgPaymentId)
            }
        }
    }

    Given("an already succeeded intent") {
        val reservationId = prepareReservation()
        val first = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()

        When("charge is called again") {
            val replay = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.AlreadySucceeded>()

            Then("it replays the stored response without calling the gateway") {
                replay.pgPaymentId shouldBe first.pgPaymentId
                gateway.chargeCount shouldBe 1
            }
        }
    }

    Given("an in-flight idempotency key") {
        val reservationId = prepareReservation()
        val key = IdempotencyKeys.chargeFull(reservationId)
        idempotency.begin(key).shouldBeInstanceOf<BeginResult.Acquired>()

        When("charge is called") {
            val result = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.InProgress>()

            Then("it reports progress instead of charging again") {
                result.idempotencyKey shouldBe key
                gateway.chargeCount shouldBe 0
            }
        }
    }

    Given("a re-entrant charge while the gateway call is in flight") {
        val reservationId = prepareReservation()
        var reentrant: ChargePaymentResult? = null
        gatewayBehaviour = {
            reentrant = service.charge(reservationId)
            succeeded()
        }

        When("charge is called") {
            val result = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()

            Then("it does not charge twice") {
                gateway.chargeCount shouldBe 1
                reentrant.shouldBeInstanceOf<ChargePaymentResult.InProgress>()
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.CONFIRMED
                result.pgPaymentId.shouldNotBeBlank()
            }
        }
    }

    Given("a declined charge") {
        val reservationId = prepareReservation()
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId
        gatewayBehaviour = { ChargeResult.Declined("insufficient_funds") }

        When("charge is called") {
            val result = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.Declined>()

            Then("the reservation stays pending and the hold stays unconfirmed") {
                result.reason shouldBe "insufficient_funds"
                inventory.isConfirmed(holdId) shouldBe false
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.PENDING_PAYMENT

                val intent =
                    paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)).shouldNotBeNull()
                intent.status shouldBe PaymentIntentStatus.RequiresAction

                // A decline moved no money, so the key must be re-acquirable rather than frozen as terminal.
                idempotency.begin(IdempotencyKeys.chargeFull(reservationId))
                    .shouldBeInstanceOf<BeginResult.Acquired>()
            }
        }
    }

    Given("a previously declined charge") {
        val reservationId = prepareReservation()
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId
        gatewayBehaviour = { ChargeResult.Declined("insufficient_funds") }
        service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.Declined>()
        gatewayBehaviour = { succeeded() }

        When("charge is retried") {
            val retry = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()

            Then("it reaches the gateway again and can succeed") {
                gateway.chargeCount shouldBe 2
                retry.pgPaymentId shouldBe "pg_test_1"
                inventory.isConfirmed(holdId) shouldBe true
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.CONFIRMED
            }
        }
    }

    Given("a declined charge then a concurrent retry") {
        val reservationId = prepareReservation()
        gatewayBehaviour = { ChargeResult.Declined("insufficient_funds") }
        service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.Declined>()

        var reentrant: ChargePaymentResult? = null
        gatewayBehaviour = {
            reentrant = service.charge(reservationId)
            succeeded()
        }

        When("charge is retried") {
            service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.JustSucceeded>()

            Then("the concurrent caller is still held off") {
                reentrant.shouldBeInstanceOf<ChargePaymentResult.InProgress>()
                gateway.chargeCount shouldBe 2
            }
        }
    }

    Given("an expired reservation") {
        val reservationId = prepareReservation()
        val reservation = reservations.findById(reservationId).shouldNotBeNull()
        reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))

        When("charge is called") {
            val result = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.Expired>()

            Then("it is rejected before the gateway") {
                result.code shouldBe ChargePaymentResult.INTENT_EXPIRED
                gateway.chargeCount shouldBe 0
            }
        }
    }

    Given("a reservation whose intent TTL has elapsed") {
        val reservationId = prepareReservation()
        now = now.plusSeconds(16 * 60)

        When("charge is called") {
            service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.Expired>()

            Then("it is rejected before the gateway") {
                gateway.chargeCount shouldBe 0
            }
        }
    }

    Given("a charge whose TTL elapses during the gateway call") {
        val reservationId = prepareReservation()
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId
        gatewayBehaviour = {
            now = now.plusSeconds(16 * 60)
            succeeded()
        }

        When("charge is called") {
            val result = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.LateSuccessMismatch>()

            Then("it does not silently confirm") {
                result.code shouldBe ChargePaymentResult.LATE_SUCCESS_AFTER_EXPIRE
                result.pgPaymentId shouldBe "pg_test_1"
                inventory.isConfirmed(holdId) shouldBe false
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.PENDING_PAYMENT

                // The money moved, so the intent keeps the gateway id and the record is terminal: never charge again.
                val intent =
                    paymentIntents.findByIdempotencyKey(IdempotencyKeys.chargeFull(reservationId)).shouldNotBeNull()
                intent.status shouldBe PaymentIntentStatus.Succeeded
                intent.pgPaymentId shouldBe "pg_test_1"
                val record =
                    idempotency.begin(IdempotencyKeys.chargeFull(reservationId))
                        .shouldBeInstanceOf<BeginResult.Existing>()
                record.entry.terminal shouldBe true
            }
        }
    }

    Given("a reservation expired during the gateway call") {
        val reservationId = prepareReservation()
        val holdId = reservations.findById(reservationId).shouldNotBeNull().holdId
        gatewayBehaviour = {
            val reservation = reservations.findById(reservationId).shouldNotBeNull()
            reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))
            inventory.releaseHold(holdId)
            succeeded()
        }

        When("charge is called") {
            service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.LateSuccessMismatch>()

            Then("it does not confirm the hold") {
                inventory.isConfirmed(holdId) shouldBe false
                reservations.findById(reservationId).shouldNotBeNull().status shouldBe ReservationStatus.EXPIRED
            }
        }
    }

    Given("a mismatched charge already recorded") {
        val reservationId = prepareReservation()
        gatewayBehaviour = {
            val reservation = reservations.findById(reservationId).shouldNotBeNull()
            reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))
            succeeded()
        }
        service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.LateSuccessMismatch>()

        When("charge is called again") {
            val replay = service.charge(reservationId).shouldBeInstanceOf<ChargePaymentResult.LateSuccessMismatch>()

            Then("it replays as a mismatch not as a success") {
                replay.pgPaymentId shouldBe "pg_test_1"
                gateway.chargeCount shouldBe 1
            }
        }
    }

    Given("an outer transaction is already active") {
        val reservationId = prepareReservation()

        When("charge is called inside that transaction") {
            Then("it refuses to run") {
                val failure =
                    shouldThrow<IllegalStateException> {
                        transactions.execute { service.charge(reservationId) }
                    }
                failure.message.shouldNotBeNull().shouldContain("must not run inside an outer transaction")
                gateway.chargeCount shouldBe 0
            }
        }
    }
})
