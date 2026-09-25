package co.handys.api.v1.mvp

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@SpringBootTest
@AutoConfigureMockMvc
class MvpFlowTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val clock: Clock,
) {
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))

    @Test
    fun `quote shows lead gate on checkin day when sold out`() {
        val today = today()
        mockMvc.get("/api/v1/channels/direct/quote") {
            param("propertyId", "P-SEOUL-01")
            param("roomTypeId", "RT-DELUXE")
            param("date", today.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.available") { value(0) }
            jsonPath("$.reason") { value("OVERBOOK_GATE_LEAD") }
        }
    }

    @Test
    fun `key blocked when room dirty`() {
        mockMvc.post("/api/v1/reservations/R-1001/key") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.reason") { value("READINESS_NOT_MET") }
            jsonPath("$.blocker") { value("room_ready") }
        }
    }

    @Test
    fun `book rejected when inventory full on checkin day`() {
        val today = today()
        mockMvc.post("/api/v1/channels/direct/bookings") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "propertyId":"P-SEOUL-01",
                  "roomTypeId":"RT-DELUXE",
                  "checkIn":"$today",
                  "checkOut":"${today.plusDays(1)}",
                  "guestName":"테스트",
                  "guestPhone":"01000000000",
                  "guestEmail":"t@example.com"
                }
            """.trimIndent()
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.reason") { value("OVERBOOK_GATE_LEAD") }
        }
    }
}
