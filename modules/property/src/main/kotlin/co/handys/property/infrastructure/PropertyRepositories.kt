package co.handys.property.infrastructure

import org.springframework.data.jpa.repository.JpaRepository

interface PropertyJpaRepository : JpaRepository<PropertyEntity, String>
interface RoomTypeJpaRepository : JpaRepository<RoomTypeEntity, String>
interface PropertyUnitJpaRepository : JpaRepository<PropertyUnitEntity, String> {
    fun findByRoomTypeId(roomTypeId: String): List<PropertyUnitEntity>
}
