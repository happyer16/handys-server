package co.handys.property.infrastructure

import co.handys.common.domain.SellMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "property_property")
class PropertyEntity(
    @Id @Column(name = "id", length = 64) var id: String = "",
    @Column(name = "timezone", nullable = false, length = 64) var timezone: String = "Asia/Seoul",
    @Column(name = "stale_ttl_sec", nullable = false) var staleTtlSec: Int = 300,
)

@Entity
@Table(name = "property_room_type")
class RoomTypeEntity(
    @Id @Column(name = "id", length = 64) var id: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Enumerated(EnumType.STRING) @Column(name = "sell_mode", nullable = false, length = 32)
    var sellMode: SellMode = SellMode.HOTEL_POOL,
    @Column(name = "capacity", nullable = false) var capacity: Int = 0,
    @Column(name = "min_lead_days", nullable = false) var minLeadDays: Int = 3,
    @Column(name = "min_capacity_for_overbook", nullable = false) var minCapacityForOverbook: Int = 10,
    @Column(name = "overbook_rate", nullable = false) var overbookRate: Double = 0.0,
    @Column(name = "list_price", precision = 12, scale = 2) var listPrice: BigDecimal? = null,
)

@Entity
@Table(name = "property_unit")
class PropertyUnitEntity(
    @Id @Column(name = "unit_id", length = 64) var unitId: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Column(name = "room_type_id", nullable = false, length = 64) var roomTypeId: String = "",
    @Column(name = "hk_status", nullable = false, length = 32) var hkStatus: String = "Dirty",
    @Column(name = "occupied", nullable = false) var occupied: Boolean = false,
)
