package co.handys.booking.payment.application

import java.time.Instant
import java.time.LocalDate

enum class SettlementBatchJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class SettlementBatchJob(
    val id: String,
    val jobDate: LocalDate,
    val status: SettlementBatchJobStatus,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val errorMessage: String? = null,
)

/** Acquires at-most-once execution per calendar day via UNIQUE(job_date). */
interface SettlementBatchJobLock {
    /** @return acquired job if this caller owns the day; null if already taken */
    fun tryAcquire(jobDate: LocalDate, now: Instant): SettlementBatchJob?

    fun markSucceeded(id: String, now: Instant)

    fun markFailed(id: String, now: Instant, errorMessage: String)

    fun findByJobDate(jobDate: LocalDate): SettlementBatchJob?
}
