package co.handys.config

import co.handys.booking.api.BookingApi
import co.handys.booking.api.StayReservationView
import co.handys.booking.application.InMemoryStayBookingService
import co.handys.channel.api.ChannelApi
import co.handys.channel.application.InMemoryChannelService
import co.handys.checkin.api.CheckinApi
import co.handys.checkin.application.InMemoryCheckinService
import co.handys.inventory.api.InventoryApi
import co.handys.inventory.application.InMemoryInventoryService
import co.handys.property.api.PropertyApi
import co.handys.property.application.InMemoryPropertyService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@Configuration
class CmsModuleConfiguration {
    @Bean
    fun propertyApi(): PropertyApi =
        InMemoryPropertyService().also { it.seedDemo() }

    @Bean
    fun bookingApi(inventoryApi: InventoryApi, propertyApi: PropertyApi, clock: Clock): BookingApi {
        val inventory = inventoryApi as InMemoryInventoryService
        val booking = InMemoryStayBookingService(inventory, propertyApi)
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.ofInstant(clock.instant(), zone)
        inventory.seedSold("P-SEOUL-01", "RT-DELUXE", today, 10)
        booking.seed(
            StayReservationView(
                id = "R-1001",
                propertyId = "P-SEOUL-01",
                roomTypeId = "RT-DELUXE",
                unitId = "U-301",
                channelId = "direct",
                status = "CONFIRMED",
                checkIn = today,
                checkOut = today.plusDays(2),
                guestName = "김민수",
                guestPhone = "01012345678",
                guestEmail = "hong@example.com",
                paymentStatus = "PAID",
                identityVerified = true,
            ),
        )
        return booking
    }

    @Bean
    fun checkinApi(bookingApi: BookingApi, propertyApi: PropertyApi): CheckinApi =
        InMemoryCheckinService(bookingApi, propertyApi)

    @Bean
    fun channelApi(
        inventoryApi: InventoryApi,
        bookingApi: BookingApi,
        propertyApi: PropertyApi,
        clock: Clock,
    ): ChannelApi {
        val channel = InMemoryChannelService(inventoryApi, bookingApi, propertyApi) { clock.instant() }
        channel.seedSynced("direct")
        channel.seedSynced("ota_a")
        channel.seedSynced("ota_b")
        return channel
    }
}
