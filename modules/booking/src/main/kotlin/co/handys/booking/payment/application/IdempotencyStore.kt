package co.handys.booking.payment.application

interface IdempotencyStore {
    fun begin(key: String): BeginResult

    fun complete(key: String, responsePayload: String, terminal: Boolean)
}

data class IdempotencyEntry(
    val key: String,
    val payload: String?,
    val terminal: Boolean,
)

sealed class BeginResult {
    data object Acquired : BeginResult()

    data class Existing(val entry: IdempotencyEntry) : BeginResult()
}
