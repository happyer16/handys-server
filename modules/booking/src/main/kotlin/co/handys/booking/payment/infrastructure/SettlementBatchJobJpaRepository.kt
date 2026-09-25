package co.handys.booking.payment.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SettlementBatchJobJpaRepository : JpaRepository<SettlementBatchJobEntity, String> {
    fun findByJobDate(jobDate: LocalDate): SettlementBatchJobEntity?

    fun existsByJobDate(jobDate: LocalDate): Boolean
}
