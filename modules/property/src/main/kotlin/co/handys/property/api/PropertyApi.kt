package co.handys.property.api

import co.handys.common.domain.SellMode
import java.math.BigDecimal

data class PropertyView(val id: String, val timezone: String, val staleTtlSec: Int)

data class RoomTypeView(
    val id: String,
    val propertyId: String,
    val mode: SellMode,
    val capacity: Int,
    val minLeadDays: Int,
    val minCapacityForOverbook: Int,
    val overbookRate: Double,
    val listPrice: BigDecimal?,
)

data class UnitView(
    val unitId: String,
    val propertyId: String,
    val roomTypeId: String,
    val hkStatus: String,
    val occupied: Boolean,
)

interface PropertyApi {
    fun getProperty(propertyId: String): PropertyView?
    fun getRoomType(roomTypeId: String): RoomTypeView?
    fun getUnit(unitId: String): UnitView?
    fun listUnitsByRoomType(roomTypeId: String): List<UnitView>
    fun updateHkStatus(unitId: String, hkStatus: String): UnitView?
}
