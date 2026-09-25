package co.handys.booking.payment.application

import co.handys.booking.payment.domain.IdempotencyKeys
import java.time.Clock
import java.time.Instant
import java.util.UUID

class PostOtaPayoutService(
    private val payouts: OtaPayoutRepository,
    private val idempotency: IdempotencyStore,
    private val clock: Clock,
) {
    fun post(cmd: PostOtaPayoutCommand): OtaPayout {
        val key = IdempotencyKeys.payout(cmd.channel, cmd.channelPayoutId)
        when (val begun = idempotency.begin(key)) {
            is BeginResult.Existing -> {
                if (begun.entry.terminal) {
                    return payouts.findByChannelPayoutId(cmd.channel, cmd.channelPayoutId)
                        ?: error("terminal payout missing for $key")
                }
            }
            BeginResult.Acquired -> Unit
        }
        val existing = payouts.findByChannelPayoutId(cmd.channel, cmd.channelPayoutId)
        if (existing != null) {
            idempotency.complete(key, existing.id, terminal = true)
            return existing
        }
        val payout =
            OtaPayout(
                id = UUID.randomUUID().toString(),
                channel = cmd.channel,
                channelPayoutId = cmd.channelPayoutId,
                propertyId = cmd.propertyId,
                amountWon = cmd.amountWon,
                reservationIds = cmd.reservationIds,
                status = OtaPayoutStatus.POSTED,
                postedAt = clock.instant(),
            )
        payouts.save(payout)
        idempotency.complete(key, payout.id, terminal = true)
        return payout
    }
}

data class PostOtaPayoutCommand(
    val channel: String,
    val channelPayoutId: String,
    val propertyId: String,
    val amountWon: Long,
    val reservationIds: List<String>,
)

data class OtaPayout(
    val id: String,
    val channel: String,
    val channelPayoutId: String,
    val propertyId: String,
    val amountWon: Long,
    val reservationIds: List<String>,
    val status: OtaPayoutStatus,
    val postedAt: Instant,
)

enum class OtaPayoutStatus {
    POSTED,
}

interface OtaPayoutRepository {
    fun save(payout: OtaPayout): OtaPayout

    fun findByChannelPayoutId(channel: String, channelPayoutId: String): OtaPayout?

    fun findPostedInPropertyMonth(propertyId: String, year: Int, month: Int): List<OtaPayout>
}
