package co.handys.inventory.infrastructure

import co.handys.common.domain.SellMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "inventory_sold")
class InventorySoldEntity(
    @Id @Column(name = "id", length = 128) var id: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Column(name = "room_type_id", nullable = false, length = 64) var roomTypeId: String = "",
    @Column(name = "stay_date", nullable = false) var stayDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "sold", nullable = false) var sold: Int = 0,
)

@Entity
@Table(name = "inventory_hold")
class InventoryHoldEntity(
    @Id @Column(name = "hold_id", length = 64) var holdId: String = "",
    @Column(name = "slot_key", nullable = false, unique = true, length = 256) var slotKey: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Column(name = "room_type_or_unit_id", nullable = false, length = 64) var roomTypeOrUnitId: String = "",
    @Column(name = "check_in", nullable = false) var checkIn: LocalDate = LocalDate.EPOCH,
    @Column(name = "check_out", nullable = false) var checkOut: LocalDate = LocalDate.EPOCH,
    @Enumerated(EnumType.STRING) @Column(name = "mode", nullable = false, length = 32)
    var mode: SellMode = SellMode.HOTEL_POOL,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "status", nullable = false, length = 32) var status: String = "HELD",
)
