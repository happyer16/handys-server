package co.handys.common.domain

enum class SellMode {
    /** Airbnb-like: unit fixed at booking, no overbooking. */
    SPECIFIC_UNIT,

    /** Hotel pool: room type at booking, unit at check-in. */
    HOTEL_POOL,
}
