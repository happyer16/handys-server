package co.handys.api.v1.channel

import co.handys.api.common.ApiException
import co.handys.api.v1.channel.request.BookHotelRequest
import co.handys.api.v1.channel.response.BookHotelResponse
import co.handys.api.v1.channel.response.SyncResponse
import co.handys.channel.api.ChannelApi
import co.handys.channel.api.ChannelBookResult
import co.handys.channel.api.ChannelQuoteRequest
import co.handys.common.domain.ReasonCode
import co.handys.inventory.api.DayQuote
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@RestController
@RequestMapping("/api/v1/channels/{channelId}")
@Tag(name = "Channel")
class ChannelV1Controller(
    private val channelApi: ChannelApi,
    private val clock: Clock,
) {
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))

    @GetMapping("/quote")
    @Operation(summary = "채널 재고 견적(pull)")
    fun quote(
        @PathVariable channelId: String,
        @RequestParam propertyId: String,
        @RequestParam roomTypeId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): DayQuote =
        channelApi.quote(channelId, ChannelQuoteRequest(propertyId, roomTypeId, date), today())
            ?: throw ApiException(HttpStatus.NOT_FOUND, ReasonCode.NOT_FOUND, "Room type or property not found")

    @PostMapping("/bookings")
    @Operation(summary = "호텔형 예약 요청(push stub)")
    fun book(
        @PathVariable channelId: String,
        @RequestBody body: BookHotelRequest,
    ): BookHotelResponse =
        when (
            val result = channelApi.bookHotel(
                channelId = channelId,
                propertyId = body.propertyId,
                roomTypeId = body.roomTypeId,
                checkIn = body.checkIn,
                checkOut = body.checkOut,
                guestName = body.guestName,
                guestPhone = body.guestPhone,
                guestEmail = body.guestEmail,
                today = today(),
            )
        ) {
            is ChannelBookResult.Ok ->
                BookHotelResponse(id = result.reservation.id, status = result.reservation.status)
            is ChannelBookResult.Rejected ->
                throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, result.reason, "Booking rejected")
        }

    @PostMapping("/sync")
    @Operation(summary = "채널 sync 시각 갱신")
    fun sync(@PathVariable channelId: String): SyncResponse {
        channelApi.markSynced(channelId)
        return SyncResponse(channelId = channelId, ok = true)
    }
}
