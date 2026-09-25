package co.handys.booking.payment.infrastructure

import co.handys.booking.domain.PaymentSource
import co.handys.booking.domain.Reservation
import co.handys.booking.domain.ReservationStatus
import co.handys.booking.payment.application.BeginResult
import co.handys.booking.payment.application.IdempotencyEntry
import co.handys.booking.payment.application.IdempotencyStore
import co.handys.booking.payment.application.MismatchQueue
import co.handys.booking.payment.application.OtaPayout
import co.handys.booking.payment.application.OtaPayoutRepository
import co.handys.booking.payment.application.OtaPayoutStatus
import co.handys.booking.payment.application.PaymentIntentRepository
import co.handys.booking.payment.application.PgEventDedupStore
import co.handys.booking.payment.application.RefundRecord
import co.handys.booking.payment.application.RefundRepository
import co.handys.booking.payment.application.ReservationRepository
import co.handys.booking.payment.application.SettlementRun
import co.handys.booking.payment.application.SettlementRunRepository
import co.handys.booking.payment.application.SettlementRunStatus
import co.handys.booking.payment.domain.PaymentIntent
import co.handys.booking.payment.domain.PaymentIntentStatus
import co.handys.booking.payment.domain.PaymentMismatch
import co.handys.booking.payment.domain.PaymentMismatchReason
import co.handys.common.domain.SellMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@Entity
@Table(name = "pay_reservation")
class PayReservationEntity(
    @Id @Column(name = "id", length = 64) var id: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Column(name = "room_type_or_unit_id", nullable = false, length = 64) var roomTypeOrUnitId: String = "",
    @Column(name = "check_in", nullable = false) var checkIn: LocalDate = LocalDate.EPOCH,
    @Column(name = "check_out", nullable = false) var checkOut: LocalDate = LocalDate.EPOCH,
    @Column(name = "amount_won", nullable = false) var amountWon: Long = 0,
    @Enumerated(EnumType.STRING) @Column(name = "mode", nullable = false, length = 32)
    var mode: SellMode = SellMode.HOTEL_POOL,
    @Column(name = "hold_id", nullable = false, length = 64) var holdId: String = "",
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32)
    var status: ReservationStatus = ReservationStatus.PENDING_PAYMENT,
    @Enumerated(EnumType.STRING) @Column(name = "payment_source", nullable = false, length = 16)
    var paymentSource: PaymentSource = PaymentSource.DIRECT,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH,
)

interface PayReservationJpaRepository : JpaRepository<PayReservationEntity, String> {
    fun findByStatus(status: ReservationStatus): List<PayReservationEntity>

    @Query("SELECT DISTINCT r.propertyId FROM PayReservationEntity r")
    fun findDistinctPropertyIds(): List<String>
}

@Entity
@Table(name = "pay_intent")
class PayIntentEntity(
    @Id @Column(name = "id", length = 64) var id: String = "",
    @Column(name = "reservation_id", nullable = false, length = 64) var reservationId: String = "",
    @Column(name = "amount_won", nullable = false) var amountWon: Long = 0,
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128) var idempotencyKey: String = "",
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32)
    var status: PaymentIntentStatus = PaymentIntentStatus.RequiresAction,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH,
    @Column(name = "pg_payment_id", length = 128) var pgPaymentId: String? = null,
)

interface PayIntentJpaRepository : JpaRepository<PayIntentEntity, String> {
    fun findByIdempotencyKey(idempotencyKey: String): PayIntentEntity?
}

@Entity
@Table(name = "pay_idempotency")
class PayIdempotencyEntity(
    @Id @Column(name = "idem_key", length = 128) var key: String = "",
    @Column(name = "payload", length = 4000) var payload: String? = null,
    @Column(name = "terminal", nullable = false) var terminal: Boolean = false,
    @Column(name = "in_flight", nullable = false) var inFlight: Boolean = false,
)

interface PayIdempotencyJpaRepository : JpaRepository<PayIdempotencyEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PayIdempotencyEntity e WHERE e.key = :key")
    fun findForUpdate(@Param("key") key: String): PayIdempotencyEntity?
}

@Entity
@Table(name = "pay_refund")
class PayRefundEntity(
    @Id @Column(name = "cancel_event_id", length = 128) var cancelEventId: String = "",
    @Column(name = "reservation_id", nullable = false, unique = true, length = 64) var reservationId: String = "",
    @Column(name = "amount_won", nullable = false) var amountWon: Long = 0,
    @Column(name = "pg_refund_id", nullable = false, length = 128) var pgRefundId: String = "",
    @Column(name = "idempotency_key", nullable = false, length = 128) var idempotencyKey: String = "",
)

interface PayRefundJpaRepository : JpaRepository<PayRefundEntity, String> {
    fun findByReservationId(reservationId: String): PayRefundEntity?
}

@Entity
@Table(name = "pay_ota_payout")
class PayOtaPayoutEntity(
    @Id @Column(name = "id", length = 64) var id: String = "",
    @Column(name = "channel", nullable = false, length = 64) var channel: String = "",
    @Column(name = "channel_payout_id", nullable = false, length = 128) var channelPayoutId: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Column(name = "amount_won", nullable = false) var amountWon: Long = 0,
    @Column(name = "reservation_ids", nullable = false, length = 2000) var reservationIdsCsv: String = "",
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16)
    var status: OtaPayoutStatus = OtaPayoutStatus.POSTED,
    @Column(name = "posted_at", nullable = false) var postedAt: Instant = Instant.EPOCH,
)

interface PayOtaPayoutJpaRepository : JpaRepository<PayOtaPayoutEntity, String> {
    fun findByChannelAndChannelPayoutId(channel: String, channelPayoutId: String): PayOtaPayoutEntity?
    fun findByPropertyId(propertyId: String): List<PayOtaPayoutEntity>
}

@Entity
@Table(name = "pay_settlement_run")
class PaySettlementRunEntity(
    @Id @Column(name = "idempotency_key", length = 128) var idempotencyKey: String = "",
    @Column(name = "run_id", nullable = false, length = 64) var runId: String = "",
    @Column(name = "property_id", nullable = false, length = 64) var propertyId: String = "",
    @Column(name = "period", nullable = false, length = 16) var period: String = "",
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16)
    var status: SettlementRunStatus = SettlementRunStatus.SUCCEEDED,
    @Column(name = "owner_payout_won", nullable = false) var ownerPayoutWon: Long = 0,
    @Column(name = "error_code", length = 64) var errorCode: String? = null,
    @Column(name = "lines_json", length = 8000) var linesJson: String = "[]",
)

interface PaySettlementRunJpaRepository : JpaRepository<PaySettlementRunEntity, String>

@Entity
@Table(name = "pay_mismatch")
class PayMismatchEntity(
    @Id @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    var id: Long? = null,
    @Enumerated(EnumType.STRING) @Column(name = "reason", nullable = false, length = 64)
    var reason: PaymentMismatchReason = PaymentMismatchReason.LATE_SUCCESS_AFTER_EXPIRE,
    @Column(name = "reservation_id", nullable = false, length = 64) var reservationId: String = "",
    @Column(name = "pg_event_id", nullable = false, length = 128) var pgEventId: String = "",
    @Column(name = "pg_payment_id", nullable = false, length = 128) var pgPaymentId: String = "",
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH,
)

interface PayMismatchJpaRepository : JpaRepository<PayMismatchEntity, Long>

@Entity
@Table(name = "pay_pg_event")
class PayPgEventEntity(
    @Id @Column(name = "pg_event_id", length = 128) var pgEventId: String = "",
)

interface PayPgEventJpaRepository : JpaRepository<PayPgEventEntity, String>

@Service
class JpaReservationRepository(
    private val repo: PayReservationJpaRepository,
) : ReservationRepository {
    @Transactional
    override fun save(reservation: Reservation): Reservation {
        repo.save(reservation.toEntity())
        return reservation
    }

    @Transactional(readOnly = true)
    override fun findById(id: String): Reservation? =
        repo.findById(id).map { it.toDomain() }.orElse(null)

    @Transactional(readOnly = true)
    override fun findByStatus(status: ReservationStatus): List<Reservation> =
        repo.findByStatus(status).map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findDistinctPropertyIds(): Set<String> =
        repo.findDistinctPropertyIds().toSet()
}

@Service
class JpaPaymentIntentRepository(
    private val repo: PayIntentJpaRepository,
) : PaymentIntentRepository {
    @Transactional
    override fun save(intent: PaymentIntent): PaymentIntent {
        repo.save(intent.toEntity())
        return intent
    }

    @Transactional(readOnly = true)
    override fun findById(id: String): PaymentIntent? =
        repo.findById(id).map { it.toDomain() }.orElse(null)

    @Transactional(readOnly = true)
    override fun findByIdempotencyKey(idempotencyKey: String): PaymentIntent? =
        repo.findByIdempotencyKey(idempotencyKey)?.toDomain()
}

@Service
class JpaIdempotencyStore(
    private val repo: PayIdempotencyJpaRepository,
) : IdempotencyStore {
    @Transactional
    override fun begin(key: String): BeginResult {
        var row = repo.findForUpdate(key)
        if (row == null) {
            repo.save(PayIdempotencyEntity(key = key, payload = null, terminal = false, inFlight = true))
            return BeginResult.Acquired
        }
        if (row.terminal || row.inFlight) {
            return BeginResult.Existing(
                IdempotencyEntry(row.key, row.payload, row.terminal, row.inFlight),
            )
        }
        row.inFlight = true
        row.payload = null
        repo.save(row)
        return BeginResult.Acquired
    }

    @Transactional
    override fun complete(key: String, responsePayload: String, terminal: Boolean) {
        val row = repo.findForUpdate(key) ?: throw IllegalArgumentException("unknown idempotency key: $key")
        row.payload = responsePayload
        row.terminal = terminal
        row.inFlight = false
        repo.save(row)
    }
}

@Service
class JpaRefundRepository(
    private val repo: PayRefundJpaRepository,
) : RefundRepository {
    @Transactional
    override fun save(record: RefundRecord): RefundRecord {
        repo.save(
            PayRefundEntity(
                record.cancelEventId, record.reservationId, record.amountWon,
                record.pgRefundId, record.idempotencyKey,
            ),
        )
        return record
    }

    @Transactional(readOnly = true)
    override fun findByReservationId(reservationId: String): RefundRecord? =
        repo.findByReservationId(reservationId)?.let {
            RefundRecord(it.cancelEventId, it.reservationId, it.amountWon, it.pgRefundId, it.idempotencyKey)
        }
}

@Service
class JpaOtaPayoutRepository(
    private val repo: PayOtaPayoutJpaRepository,
) : OtaPayoutRepository {
    @Transactional
    override fun save(payout: OtaPayout): OtaPayout {
        repo.save(
            PayOtaPayoutEntity(
                payout.id, payout.channel, payout.channelPayoutId, payout.propertyId, payout.amountWon,
                payout.reservationIds.joinToString(","), payout.status, payout.postedAt,
            ),
        )
        return payout
    }

    @Transactional(readOnly = true)
    override fun findByChannelPayoutId(channel: String, channelPayoutId: String): OtaPayout? =
        repo.findByChannelAndChannelPayoutId(channel, channelPayoutId)?.toDomain()

    @Transactional(readOnly = true)
    override fun findPostedInPropertyMonth(propertyId: String, year: Int, month: Int): List<OtaPayout> {
        val period = YearMonth.of(year, month)
        return repo.findByPropertyId(propertyId)
            .filter { YearMonth.from(it.postedAt.atZone(ZoneOffset.UTC)) == period }
            .map { it.toDomain() }
    }
}

@Service
class JpaSettlementRunRepository(
    private val repo: PaySettlementRunJpaRepository,
) : SettlementRunRepository {
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    @Transactional
    override fun save(run: SettlementRun): SettlementRun {
        repo.save(
            PaySettlementRunEntity(
                idempotencyKey = run.idempotencyKey,
                runId = run.runId,
                propertyId = run.propertyId,
                period = run.period.toString(),
                status = run.status,
                ownerPayoutWon = run.ownerPayoutWon,
                errorCode = run.errorCode,
                linesJson = mapper.writeValueAsString(run.lines),
            ),
        )
        return run
    }

    @Transactional(readOnly = true)
    override fun findByKey(idempotencyKey: String): SettlementRun? =
        repo.findById(idempotencyKey).map {
            val lines: List<co.handys.booking.payment.application.SettlementLine> =
                mapper.readValue(
                    it.linesJson,
                    mapper.typeFactory.constructCollectionType(
                        List::class.java,
                        co.handys.booking.payment.application.SettlementLine::class.java,
                    ),
                )
            SettlementRun(
                runId = it.runId,
                propertyId = it.propertyId,
                period = YearMonth.parse(it.period),
                status = it.status,
                ownerPayoutWon = it.ownerPayoutWon,
                lines = lines,
                errorCode = it.errorCode,
                idempotencyKey = it.idempotencyKey,
            )
        }.orElse(null)
}

@Service
class JpaMismatchQueue(
    private val repo: PayMismatchJpaRepository,
) : MismatchQueue {
    @Transactional
    override fun enqueue(mismatch: PaymentMismatch) {
        repo.save(
            PayMismatchEntity(
                reason = mismatch.reason,
                reservationId = mismatch.reservationId,
                pgEventId = mismatch.pgEventId,
                pgPaymentId = mismatch.pgPaymentId,
                createdAt = mismatch.createdAt,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun size(): Int = repo.count().toInt()

    @Transactional(readOnly = true)
    override fun all(): List<PaymentMismatch> =
        repo.findAll().map {
            PaymentMismatch(it.reason, it.reservationId, it.pgEventId, it.pgPaymentId, it.createdAt)
        }
}

@Service
class JpaPgEventDedupStore(
    private val repo: PayPgEventJpaRepository,
) : PgEventDedupStore {
    @Transactional
    override fun tryRecord(pgEventId: String): Boolean {
        if (repo.existsById(pgEventId)) return false
        repo.save(PayPgEventEntity(pgEventId))
        return true
    }

    @Transactional(readOnly = true)
    override fun recordedCount(): Int = repo.count().toInt()
}

private fun Reservation.toEntity() = PayReservationEntity(
    id, propertyId, roomTypeOrUnitId, checkIn, checkOut, amountWon, mode, holdId,
    status, paymentSource, expiresAt, createdAt, updatedAt,
)

private fun PayReservationEntity.toDomain() = Reservation(
    id, propertyId, roomTypeOrUnitId, checkIn, checkOut, amountWon, mode, holdId,
    status, paymentSource, expiresAt, createdAt, updatedAt,
)

private fun PaymentIntent.toEntity() = PayIntentEntity(
    id, reservationId, amountWon, idempotencyKey, status, expiresAt, createdAt, updatedAt, pgPaymentId,
)

private fun PayIntentEntity.toDomain() = PaymentIntent(
    id, reservationId, amountWon, idempotencyKey, status, expiresAt, createdAt, updatedAt, pgPaymentId,
)

private fun PayOtaPayoutEntity.toDomain() = OtaPayout(
    id, channel, channelPayoutId, propertyId, amountWon,
    if (reservationIdsCsv.isBlank()) emptyList() else reservationIdsCsv.split(","),
    status, postedAt,
)
