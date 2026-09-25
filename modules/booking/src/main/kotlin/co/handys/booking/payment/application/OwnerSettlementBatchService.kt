package co.handys.booking.payment.application

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class OwnerSettlementBatchService(
    private val lock: SettlementBatchJobLock,
    private val settlements: RunOwnerSettlementService,
    private val reservations: ReservationRepository,
    private val clock: Clock,
) {
    fun runForDate(jobDate: LocalDate): OwnerSettlementBatchResult {
        val now = clock.instant()
        val acquired = lock.tryAcquire(jobDate, now) ?: return OwnerSettlementBatchResult.Skipped(jobDate)

        val period = YearMonth.from(jobDate).minusMonths(1)
        return try {
            val propertyIds = reservations.findDistinctPropertyIds()
            val runs = propertyIds.map { propertyId -> settlements.run(propertyId, period) }
            lock.markSucceeded(acquired.id, clock.instant())
            OwnerSettlementBatchResult.Completed(
                jobDate = jobDate,
                period = period,
                jobId = acquired.id,
                runs = runs,
            )
        } catch (ex: Exception) {
            lock.markFailed(acquired.id, clock.instant(), ex.message ?: ex::class.java.simpleName)
            throw ex
        }
    }

    /** Uses today (UTC) as the job date and settles the previous calendar month. */
    fun runToday(): OwnerSettlementBatchResult =
        runForDate(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
}

sealed class OwnerSettlementBatchResult {
    data class Skipped(val jobDate: LocalDate) : OwnerSettlementBatchResult()

    data class Completed(
        val jobDate: LocalDate,
        val period: YearMonth,
        val jobId: String,
        val runs: List<SettlementRun>,
    ) : OwnerSettlementBatchResult()
}
