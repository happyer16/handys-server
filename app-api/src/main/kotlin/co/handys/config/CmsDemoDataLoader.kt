package co.handys.config

import co.handys.booking.infrastructure.StayReservationEntity
import co.handys.booking.infrastructure.StayReservationJpaRepository
import co.handys.channel.infrastructure.ChannelSyncEntity
import co.handys.channel.infrastructure.ChannelSyncJpaRepository
import co.handys.common.domain.SellMode
import co.handys.inventory.infrastructure.InventorySoldEntity
import co.handys.inventory.infrastructure.InventorySoldJpaRepository
import co.handys.property.infrastructure.PropertyEntity
import co.handys.property.infrastructure.PropertyJpaRepository
import co.handys.property.infrastructure.PropertyUnitEntity
import co.handys.property.infrastructure.PropertyUnitJpaRepository
import co.handys.property.infrastructure.RoomTypeEntity
import co.handys.property.infrastructure.RoomTypeJpaRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@Component
class CmsDemoDataLoader(
    private val properties: PropertyJpaRepository,
    private val roomTypes: RoomTypeJpaRepository,
    private val units: PropertyUnitJpaRepository,
    private val sold: InventorySoldJpaRepository,
    private val stays: StayReservationJpaRepository,
    private val sync: ChannelSyncJpaRepository,
    private val clock: Clock,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        if (properties.existsById("P-SEOUL-01")) return
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.ofInstant(clock.instant(), zone)

        properties.save(PropertyEntity("P-SEOUL-01", "Asia/Seoul", 300))
        roomTypes.save(
            RoomTypeEntity(
                id = "RT-DELUXE",
                propertyId = "P-SEOUL-01",
                sellMode = SellMode.HOTEL_POOL,
                capacity = 10,
                minLeadDays = 3,
                minCapacityForOverbook = 10,
                overbookRate = 0.0,
                listPrice = BigDecimal("120000"),
            ),
        )
        units.save(PropertyUnitEntity("U-301", "P-SEOUL-01", "RT-DELUXE", "Dirty", true))
        units.save(PropertyUnitEntity("U-302", "P-SEOUL-01", "RT-DELUXE", "Ready", false))
        sold.save(
            InventorySoldEntity(
                id = "P-SEOUL-01:RT-DELUXE:$today",
                propertyId = "P-SEOUL-01",
                roomTypeId = "RT-DELUXE",
                stayDate = today,
                sold = 10,
            ),
        )
        stays.save(
            StayReservationEntity(
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
        val now = clock.instant()
        listOf("direct", "ota_a", "ota_b").forEach { sync.save(ChannelSyncEntity(it, now)) }
    }
}
