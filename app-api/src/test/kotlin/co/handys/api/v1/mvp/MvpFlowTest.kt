package co.handys.api.v1.mvp

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
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
class MvpFlowTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var clock: Clock

    init {
        fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))

        Given("check-in day inventory is sold out") {
            When("a direct quote is requested") {
                Then("it shows the lead gate") {
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
            }
        }

        Given("a reservation whose room is dirty") {
            When("a key is requested") {
                Then("it is blocked with readiness not met") {
                    mockMvc.post("/api/v1/reservations/R-1001/key") {
                        accept = MediaType.APPLICATION_JSON
                    }.andExpect {
                        status { isUnprocessableEntity() }
                        jsonPath("$.reason") { value("READINESS_NOT_MET") }
                        jsonPath("$.blocker") { value("room_ready") }
                    }
                }
            }
        }

        Given("check-in day inventory is full") {
            When("a booking is attempted") {
                Then("it is rejected with the lead gate") {
                    val today = today()
                    mockMvc.post("/api/v1/channels/direct/bookings") {
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            """
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
        }
    }
}
