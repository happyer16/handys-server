package co.handys.batch.settlement

import co.handys.booking.payment.application.OwnerSettlementBatchResult
import co.handys.booking.payment.application.OwnerSettlementBatchService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Monthly owner settlement — runs only on app-batch (not app-api).
 * Duplicate same-day runs are blocked by UNIQUE(job_date) on SettlementBatchJobEntity.
 */
@Component
class SettlementBatchScheduler(
    private val batch: OwnerSettlementBatchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 1st of each month at 02:00 UTC — settles the previous calendar month. */
    @Scheduled(cron = "0 0 2 1 * *")
    fun runMonthlySettlement() {
        when (val result = batch.runToday()) {
            is OwnerSettlementBatchResult.Skipped ->
                log.info("settlement batch skipped (already ran for jobDate={})", result.jobDate)
            is OwnerSettlementBatchResult.Completed ->
                log.info(
                    "settlement batch completed jobDate={} period={} properties={} jobId={}",
                    result.jobDate,
                    result.period,
                    result.runs.size,
                    result.jobId,
                )
        }
    }
}
