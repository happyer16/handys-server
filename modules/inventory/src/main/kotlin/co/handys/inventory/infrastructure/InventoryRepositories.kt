package co.handys.inventory.infrastructure

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InventorySoldJpaRepository : JpaRepository<InventorySoldEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InventorySoldEntity s WHERE s.id = :id")
    fun findForUpdate(@Param("id") id: String): InventorySoldEntity?
}

interface InventoryHoldJpaRepository : JpaRepository<InventoryHoldEntity, String> {
    fun findBySlotKey(slotKey: String): InventoryHoldEntity?
}
