package co.handys.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.tags.Tag
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun handysOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Handys / Plott OS API")
                    .version("0.0.1")
                    .description(
                        """
                        과제용 프로토타입 API.
                        
                        - **결제·정산**: 다이렉트 즉시전액 + Mock PG 멱등 청구, OTA payout, 오너 월 정산 (ADR-003)
                        - **예약·체크인**: `/api/v1/...` CMS·체크인 코어
                        
                        Swagger UI: `/swagger-ui.html` · OpenAPI JSON: `/v3/api-docs`
                        """.trimIndent(),
                    ).contact(Contact().name("handys-assignment")),
            ).addTagsItem(Tag().name("Guest Payment").description("다이렉트 예약 생성·청구 (멱등 CHARGE_FULL)"))
            .addTagsItem(Tag().name("PG Webhook").description("Mock PG 성공 웹훅 (pgEventId 중복 무시)"))
            .addTagsItem(Tag().name("Admin Payment").description("OTA 입금 Posted · 월 정산 · 불일치 큐 · TTL expire"))
            .addTagsItem(Tag().name("Reservations").description("예약 조회·결제 stub·본인확인·배정·키"))
            .addTagsItem(Tag().name("Admin").description("어드민 HK 등"))
            .addTagsItem(Tag().name("Channel").description("채널/호텔형 예약 stub"))
}
