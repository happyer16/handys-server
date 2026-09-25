package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.SettlementBatchJob
import co.handys.booking.payment.application.SettlementBatchJobLock
import co.handys.booking.payment.application.SettlementBatchJobStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class JpaSettlementBatchJobLock(
    private val repo: SettlementBatchJobJpaRepository,
) : SettlementBatchJobLock {
    @Transactional
    override fun tryAcquire(jobDate: LocalDate, now: Instant): SettlementBatchJob? {
        if (repo.existsByJobDate(jobDate)) return null
        val job =
            SettlementBatchJob(
                id = UUID.randomUUID().toString(),
                jobDate = jobDate,
                status = SettlementBatchJobStatus.RUNNING,
                startedAt = now,
            )
        return try {
            repo.saveAndFlush(SettlementBatchJobEntity.from(job)).toDomain()
        } catch (_: DataIntegrityViolationException) {
            null
        }
    }

    @Transactional
    override fun markSucceeded(id: String, now: Instant) {
        val entity = repo.findById(id).orElse(null) ?: return
        entity.status = SettlementBatchJobStatus.SUCCEEDED
        entity.finishedAt = now
        entity.errorMessage = null
        repo.save(entity)
    }

    @Transactional
    override fun markFailed(id: String, now: Instant, errorMessage: String) {
        val entity = repo.findById(id).orElse(null) ?: return
        entity.status = SettlementBatchJobStatus.FAILED
        entity.finishedAt = now
        entity.errorMessage = errorMessage.take(1024)
        repo.save(entity)
    }

    @Transactional(readOnly = true)
    override fun findByJobDate(jobDate: LocalDate): SettlementBatchJob? =
        repo.findByJobDate(jobDate)?.toDomain()
}
