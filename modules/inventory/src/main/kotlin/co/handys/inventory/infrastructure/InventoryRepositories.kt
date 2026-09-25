package co.handys.inventory.infrastructure

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

interface InventorySoldJpaRepository : JpaRepository<InventorySoldEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InventorySoldEntity s WHERE s.id = :id")
    fun findForUpdate(@Param("id") id: String): InventorySoldEntity?
}

interface InventoryHoldJpaRepository : JpaRepository<InventoryHoldEntity, String> {
    fun findBySlotKey(slotKey: String): InventoryHoldEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM InventoryHoldEntity h WHERE h.holdId = :holdId")
    fun findForUpdate(@Param("holdId") holdId: String): InventoryHoldEntity?

    @Query(
        """
        SELECT COUNT(h) FROM InventoryHoldEntity h
        WHERE h.status = 'HELD'
          AND h.propertyId = :propertyId
          AND h.roomTypeOrUnitId = :roomTypeId
          AND h.checkIn <= :night AND h.checkOut > :night
        """,
    )
    fun countHeldCoveringNight(
        @Param("propertyId") propertyId: String,
        @Param("roomTypeId") roomTypeId: String,
        @Param("night") night: LocalDate,
    ): Long

    @Query("SELECT h FROM InventoryHoldEntity h WHERE h.status = 'HELD' AND h.expiresAt <= :now")
    fun findExpiredHeld(@Param("now") now: Instant): List<InventoryHoldEntity>

    @Query("SELECT h FROM InventoryHoldEntity h WHERE h.status = 'HELD' AND h.expiresAt > :now")
    fun findActiveHeld(@Param("now") now: Instant): List<InventoryHoldEntity>
}

interface InventoryUnitNightJpaRepository : JpaRepository<InventoryUnitNightEntity, String> {
    fun findAllByHoldId(holdId: String): List<InventoryUnitNightEntity>

    @Modifying
    @Query("DELETE FROM InventoryUnitNightEntity u WHERE u.holdId = :holdId")
    fun deleteByHoldId(@Param("holdId") holdId: String): Int
}

interface InventoryRedisModeJpaRepository : JpaRepository<InventoryRedisModeEntity, String>
