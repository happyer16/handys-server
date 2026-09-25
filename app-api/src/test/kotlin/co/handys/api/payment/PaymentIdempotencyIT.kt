package co.handys.api.payment

import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.application.ReservationRepository
import co.handys.booking.payment.infrastructure.MockChargeBehavior
import co.handys.booking.payment.infrastructure.MockPaymentGateway
import co.handys.common.domain.SellMode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class PaymentIdempotencyIT {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var reservations: ReservationRepository

    @BeforeEach
    fun resetMockPg() {
        MockPaymentGateway.resetControls()
        MockPaymentGateway.nextBehavior = MockChargeBehavior.SUCCEED
    }

    @Test
    fun `TC-PAY-IDEM-01 five charges confirm once`() {
        val reservationId = createReservation()
        repeat(5) {
            mockMvc.post("/guest/reservations/$reservationId/charge")
                .andExpect { status { isOk() } }
        }
        assertEquals(ReservationStatus.CONFIRMED, reservations.findById(reservationId)!!.status)
    }

    @Test
    fun `TC-PAY-STATE-01 decline does not confirm`() {
        val reservationId = createReservation()
        MockPaymentGateway.nextBehavior = MockChargeBehavior.DECLINE
        mockMvc.post("/guest/reservations/$reservationId/charge")
            .andExpect { status { isUnprocessableEntity() } }
        assertEquals(ReservationStatus.PENDING_PAYMENT, reservations.findById(reservationId)!!.status)
    }

    @Test
    fun `TC-PAY-MISMATCH-01 expire then webhook enqueues mismatch`() {
        val reservationId = createReservation()
        mockMvc.post("/admin/expire-payments").andExpect { status { isOk() } }
        // Force expire immediately by advancing — expire job uses reservation.expiresAt; for IT
        // overwrite status to EXPIRED like late path.
        val reservation = reservations.findById(reservationId)!!
        reservations.save(reservation.copy(status = ReservationStatus.EXPIRED))

        mockMvc.post("/webhooks/mock-pg") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"pgEventId":"evt_late_$reservationId","reservationId":"$reservationId","pgPaymentId":"pg_late"}"""
        }.andExpect { status { isOk() } }

        mockMvc.get("/admin/mismatches")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].reservationId") { value(reservationId) }
            }
        assertEquals(ReservationStatus.EXPIRED, reservations.findById(reservationId)!!.status)
    }

    @Test
    fun `TC-STL-IDEM-01 settlement run is idempotent`() {
        val reservationId = createReservation(checkOut = LocalDate.now().plusDays(2))
        // ensure checkout month matches settlement period
        val reservation = reservations.findById(reservationId)!!
        val checkout = LocalDate.now().withDayOfMonth(1).plusMonths(0).plusDays(10)
        reservations.save(
            reservation.copy(
                checkIn = checkout.minusDays(1),
                checkOut = checkout,
            ),
        )
        mockMvc.post("/guest/reservations/$reservationId/charge").andExpect { status { isOk() } }

        val period = java.time.YearMonth.from(checkout).toString()
        val first =
            mockMvc.post("/admin/settlements") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"propertyId":"prop-it","period":"$period"}"""
            }.andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString

        val second =
            mockMvc.post("/admin/settlements") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"propertyId":"prop-it","period":"$period"}"""
            }.andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString

        assertEquals(first, second)
    }

    private fun createReservation(checkOut: LocalDate = LocalDate.now().plusDays(3)): String {
        val checkIn = checkOut.minusDays(1)
        val unit = "deluxe-${java.util.UUID.randomUUID()}"
        val body =
            """
            {
              "propertyId":"prop-it",
              "roomTypeOrUnitId":"$unit",
              "checkIn":"$checkIn",
              "checkOut":"$checkOut",
              "amountWon":100000,
              "mode":"${SellMode.HOTEL_POOL}"
            }
            """.trimIndent()
        val response =
            mockMvc.post("/guest/reservations") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString
        val id = Regex(""""reservationId"\s*:\s*"([^"]+)"""").find(response)!!.groupValues[1]
        return id
    }
}