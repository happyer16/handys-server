package co.handys.booking.payment.infrastructure

import co.handys.booking.payment.application.SettlementBatchJob
import co.handys.booking.payment.application.SettlementBatchJobStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "booking_settlement_batch_job")
class SettlementBatchJobEntity(
    @Id @Column(name = "id", length = 64) var id: String = "",
    @Column(name = "job_date", nullable = false, unique = true) var jobDate: LocalDate = LocalDate.EPOCH,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: SettlementBatchJobStatus = SettlementBatchJobStatus.RUNNING,
    @Column(name = "started_at", nullable = false) var startedAt: Instant = Instant.EPOCH,
    @Column(name = "finished_at") var finishedAt: Instant? = null,
    @Column(name = "error_message", length = 1024) var errorMessage: String? = null,
) {
    fun toDomain() =
        SettlementBatchJob(
            id = id,
            jobDate = jobDate,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            errorMessage = errorMessage,
        )

    companion object {
        fun from(job: SettlementBatchJob) =
            SettlementBatchJobEntity(
                id = job.id,
                jobDate = job.jobDate,
                status = job.status,
                startedAt = job.startedAt,
                finishedAt = job.finishedAt,
                errorMessage = job.errorMessage,
            )
    }
}
