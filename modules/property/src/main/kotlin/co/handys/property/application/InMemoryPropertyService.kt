package co.handys.property.application

import co.handys.common.domain.SellMode
import co.handys.property.api.PropertyApi
import co.handys.property.api.PropertyView
import co.handys.property.api.RoomTypeView
import co.handys.property.api.UnitView
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class InMemoryPropertyService : PropertyApi {
    private val properties = ConcurrentHashMap<String, PropertyView>()
    private val roomTypes = ConcurrentHashMap<String, RoomTypeView>()
    private val units = ConcurrentHashMap<String, UnitView>()

    fun seedDemo() {
        properties["P-SEOUL-01"] = PropertyView("P-SEOUL-01", "Asia/Seoul", 300)
        roomTypes["RT-DELUXE"] = RoomTypeView(
            id = "RT-DELUXE",
            propertyId = "P-SEOUL-01",
            mode = SellMode.HOTEL_POOL,
            capacity = 10,
            minLeadDays = 3,
            minCapacityForOverbook = 10,
            overbookRate = 0.0,
            listPrice = BigDecimal("120000"),
        )
        units["U-301"] = UnitView("U-301", "P-SEOUL-01", "RT-DELUXE", "Dirty", true)
        units["U-302"] = UnitView("U-302", "P-SEOUL-01", "RT-DELUXE", "Ready", false)
    }

    override fun getProperty(propertyId: String) = properties[propertyId]
    override fun getRoomType(roomTypeId: String) = roomTypes[roomTypeId]
    override fun getUnit(unitId: String) = units[unitId]
    override fun listUnitsByRoomType(roomTypeId: String) =
        units.values.filter { it.roomTypeId == roomTypeId }

    override fun updateHkStatus(unitId: String, hkStatus: String): UnitView? {
        val cur = units[unitId] ?: return null
        val next = cur.copy(hkStatus = hkStatus)
        units[unitId] = next
        return next
    }
}
