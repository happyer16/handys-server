package co.handys.property.api

/**
 * Public facade for other modules.
 * Inventory/booking/checkin depend on this API, not on JPA entities.
 */
interface PropertyApi {
    // Ports will grow with use cases (get unit, get room type, sell mode, …)
}
