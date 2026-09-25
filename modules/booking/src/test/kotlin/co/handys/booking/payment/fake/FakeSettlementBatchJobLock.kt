package co.handys.booking.payment.fake

import co.handys.booking.payment.application.SettlementBatchJob
import co.handys.booking.payment.application.SettlementBatchJobLock
import co.handys.booking.payment.application.SettlementBatchJobStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeSettlementBatchJobLock : SettlementBatchJobLock {
    private val byDate = ConcurrentHashMap<LocalDate, SettlementBatchJob>()

    override fun tryAcquire(jobDate: LocalDate, now: Instant): SettlementBatchJob? {
        val job =
            SettlementBatchJob(
                id = UUID.randomUUID().toString(),
                jobDate = jobDate,
                status = SettlementBatchJobStatus.RUNNING,
                startedAt = now,
            )
        val existing = byDate.putIfAbsent(jobDate, job)
        return if (existing == null) job else null
    }

    override fun markSucceeded(id: String, now: Instant) {
        update(id) { it.copy(status = SettlementBatchJobStatus.SUCCEEDED, finishedAt = now, errorMessage = null) }
    }

    override fun markFailed(id: String, now: Instant, errorMessage: String) {
        update(id) {
            it.copy(
                status = SettlementBatchJobStatus.FAILED,
                finishedAt = now,
                errorMessage = errorMessage.take(1024),
            )
        }
    }

    override fun findByJobDate(jobDate: LocalDate): SettlementBatchJob? = byDate[jobDate]

    private fun update(id: String, transform: (SettlementBatchJob) -> SettlementBatchJob) {
        val entry = byDate.entries.firstOrNull { it.value.id == id } ?: return
        byDate[entry.key] = transform(entry.value)
    }
}
