package co.handys.booking.payment.fake

import co.handys.booking.payment.application.OtaPayout
import co.handys.booking.payment.application.OtaPayoutRepository
import co.handys.booking.payment.application.SettlementRun
import co.handys.booking.payment.application.SettlementRunRepository
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

class FakeOtaPayoutRepository : OtaPayoutRepository {
    private val byKey = ConcurrentHashMap<String, OtaPayout>()

    override fun save(payout: OtaPayout): OtaPayout {
        byKey["${payout.channel}:${payout.channelPayoutId}"] = payout
        return payout
    }

    override fun findByChannelPayoutId(channel: String, channelPayoutId: String): OtaPayout? =
        byKey["$channel:$channelPayoutId"]

    override fun findPostedInPropertyMonth(propertyId: String, year: Int, month: Int): List<OtaPayout> {
        val period = YearMonth.of(year, month)
        return byKey.values.filter {
            it.propertyId == propertyId && YearMonth.from(it.postedAt.atZone(java.time.ZoneOffset.UTC)) == period
        }
    }
}

class FakeSettlementRunRepository : SettlementRunRepository {
    private val byKey = ConcurrentHashMap<String, SettlementRun>()

    override fun save(run: SettlementRun): SettlementRun {
        byKey[run.idempotencyKey] = run
        return run
    }

    override fun findByKey(idempotencyKey: String): SettlementRun? = byKey[idempotencyKey]
}
