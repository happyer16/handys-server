package co.handys.booking.payment.application

interface IdempotencyStore {
    /**
     * Acquire the right to call the PG for [key].
     *
     * - no record → [BeginResult.Acquired]
     * - in-flight record → [BeginResult.Existing], the caller reports "in progress"
     * - completed **terminal** record → [BeginResult.Existing], the caller replays `payload`
     * - completed **non-terminal** record (a decline) → [BeginResult.Acquired] again, so a retry inside the
     *   TTL may reach the PG with the same key instead of replaying the decline forever (ADR-003).
     */
    fun begin(key: String): BeginResult

    /**
     * Close the in-flight record. `terminal = true` freezes the outcome and every later [begin] replays
     * [responsePayload]; `terminal = false` leaves the key retryable.
     */
    fun complete(key: String, responsePayload: String, terminal: Boolean)
}

data class IdempotencyEntry(
    val key: String,
    val payload: String?,
    val terminal: Boolean,
    /** True between [IdempotencyStore.begin] and [IdempotencyStore.complete] — a PG call may be in flight. */
    val inFlight: Boolean,
)

sealed class BeginResult {
    data object Acquired : BeginResult()

    data class Existing(val entry: IdempotencyEntry) : BeginResult()
}
