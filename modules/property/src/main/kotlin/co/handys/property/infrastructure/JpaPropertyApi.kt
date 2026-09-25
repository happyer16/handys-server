package co.handys.property.infrastructure

import co.handys.property.api.PropertyApi
import co.handys.property.api.PropertyView
import co.handys.property.api.RoomTypeView
import co.handys.property.api.UnitView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JpaPropertyApi(
    private val properties: PropertyJpaRepository,
    private val roomTypes: RoomTypeJpaRepository,
    private val units: PropertyUnitJpaRepository,
) : PropertyApi {
    @Transactional(readOnly = true)
    override fun getProperty(propertyId: String): PropertyView? =
        properties.findById(propertyId).map { PropertyView(it.id, it.timezone, it.staleTtlSec) }.orElse(null)

    @Transactional(readOnly = true)
    override fun getRoomType(roomTypeId: String): RoomTypeView? =
        roomTypes.findById(roomTypeId).map {
            RoomTypeView(
                it.id, it.propertyId, it.sellMode, it.capacity,
                it.minLeadDays, it.minCapacityForOverbook, it.overbookRate, it.listPrice,
            )
        }.orElse(null)

    @Transactional(readOnly = true)
    override fun getUnit(unitId: String): UnitView? =
        units.findById(unitId).map { it.toView() }.orElse(null)

    @Transactional(readOnly = true)
    override fun listUnitsByRoomType(roomTypeId: String): List<UnitView> =
        units.findByRoomTypeId(roomTypeId).map { it.toView() }

    @Transactional
    override fun updateHkStatus(unitId: String, hkStatus: String): UnitView? {
        val e = units.findById(unitId).orElse(null) ?: return null
        e.hkStatus = hkStatus
        return units.save(e).toView()
    }

    private fun PropertyUnitEntity.toView() =
        UnitView(unitId, propertyId, roomTypeId, hkStatus, occupied)
}
