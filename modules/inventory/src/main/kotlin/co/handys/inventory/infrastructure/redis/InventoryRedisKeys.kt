package co.handys.inventory.infrastructure.redis

import java.time.LocalDate

/** ADR-004 key schema (Redis DB index 1). */
object InventoryRedisKeys {
    fun unitNight(propertyId: String, unitId: String, night: LocalDate): String =
        "inv:hold:unit:$propertyId:$unitId:$night"

    fun poolHeld(propertyId: String, roomTypeId: String, night: LocalDate): String =
        "inv:held:pool:$propertyId:$roomTypeId:$night"

    fun holdMeta(holdId: String): String = "inv:hold:meta:$holdId"
}
