# Payment · Settlement · Idempotency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 다이렉트 예약이 Mock PG로 **한 번만** 청구되고 결제 성공 시에만 `confirmed`되며, 오너 월 정산이 동일 reservation을 이중 집계하지 않는 프로토타입을 만든다.

**Architecture:** 결제는 `module-booking` 안의 `payment` 패키지(별도 Gradle 모듈 없음 — ADR-002 YAGNI). **DB 트랜잭션을 연 채 외부 PG를 호출하지 않는다.** Prepare TX(예약+held+Intent) → PG(네트워크) → Finalize TX(Intent Succeeded + inventory confirm) 3단계. 멱등 키는 DB UNIQUE. 오너 정산은 booking 내 집계 유스케이스(정산 전용 모듈 없음).

**Tech Stack:** Kotlin 21 · Spring Boot 3.4 · Spring Data JPA · H2(test) · JUnit5 · Gradle multi-module (ADR-002)

**Spec:** `wiki/prd/payment-settlement/policy.md` · `prd.md` (status: active)  
**Depends on:** ADR-001 (`held`/`confirmed`) · ADR-002 (모듈 경계)  
**Produces:** ADR-003 (이 플랜 Task 1)

## Global Constraints

- 통화 **KRW** 정수 원. `held` TTL / Intent 만료 **15분**.
- 멱등 키 문자열 (클라이언트 생성 금지, 서버 고정):
  - `pay:{reservationId}:CHARGE_FULL`
  - `ref:{reservationId}:{cancelEventId}`
  - `payout:{channel}:{channelPayoutId}`
  - `stl:{propertyId}:{yyyy-MM}:ps-v1`
- **결제 성공 없이 다이렉트 `CONFIRMED` 금지.** TTL 만료 후 늦은 PG 성공 → **불일치 큐만**, silent confirm 금지.
- OTA 예약은 `CHARGE` Intent를 만들지 않는다 (`payment_source=OTA`).
- 플랫폼 수수료 **15%**: `owner_payout = recognized_net - floor(recognized_net * 0.15)`.
- 체크인 전 취소 환불: `cancel_at < checkin_at - 24h` → 전액, else 0. 등호(`==` 경계)는 **환불 0** (미만만 환불).
- 의존 방향: booking → inventory facade만. inventory가 booking을 알지 않음.
- **TX 규칙 (위반 시 리뷰 거부):**
  1. `@Transactional` 메서드 안에서 `PaymentGateway.charge` / HTTP / sleep 금지.
  2. Finalize TX는 Intent 상태 전이와 `InventoryApi.confirmHold`를 **같은 로컬 트랜잭션**에서만.
  3. 동일 `idempotency_key`로 Succeeded면 PG charge **재호출 금지**, 저장된 결과 반환.

---

## File map (생성·역할)

```
wiki/decisions/003-payment-idempotency-tx.md     # ADR-003

modules/common/.../Money.kt                      # 원 단위 value type (선택, 또는 Long)
modules/common/.../ClockPort.kt                  # 테스트 가능 시계

modules/inventory/.../InventoryApi.kt            # hold / confirmHold / releaseHold
modules/inventory/.../domain/HoldId.kt
modules/inventory/.../application/InventoryService.kt
# (최소 구현 — 오버북 게이트 풀셋은 CMS 플랜 범위. 여기선 hold 카운트만)

modules/booking/.../payment/domain/
  PaymentIntent.kt                               # 상태머신
  PaymentIntentStatus.kt
  IdempotencyKey.kt
  PaymentMismatch.kt
modules/booking/.../payment/application/
  CreateDirectReservationService.kt              # Prepare TX
  ChargePaymentService.kt                        # PG outside TX + Finalize
  HandlePgWebhookService.kt
  ExpirePaymentIntentsJob.kt
  CancelReservationService.kt                    # 환불 이진
  PostOtaPayoutService.kt
  RunOwnerSettlementService.kt
modules/booking/.../payment/infrastructure/
  PaymentIntentEntity.kt / JpaRepo
  IdempotencyRecordEntity.kt                     # UNIQUE(idempotency_key)
  MockPaymentGateway.kt
  PgWebhookController 는 app-api로
modules/booking/.../payment/api/PaymentFacade.kt

modules/booking/.../domain/Reservation.kt
modules/booking/.../infrastructure/ReservationEntity.kt

app-api/.../payment/GuestPaymentController.kt
app-api/.../payment/AdminSettlementController.kt
app-api/.../payment/MockPgAdminController.kt     # 지연/중복 웹훅 시뮬
app-api/.../payment/PgWebhookController.kt

modules/booking/src/test/.../payment/           # 단위·유스케이스 테스트
app-api/src/test/.../PaymentIdempotencyIT.kt    # 연타·웹훅 중복 IT
```

---

### Task 1: ADR-003 — 멱등성 · 트랜잭션 단위

**Files:**
- Create: `wiki/decisions/003-payment-idempotency-tx.md`
- Modify: `wiki/README.md` (인덱스 한 줄)

**Interfaces:**
- Produces: Accepted(또는 Proposed→바로 Accepted) 규칙 — 이후 Task가 이 문서를 법률로 따름

- [ ] **Step 1: ADR 초안 작성**

아래 내용을 빠짐없이 넣는다 (tpl-adr 섹션 유지).

**결정 요약 (본문에 표로):**

| 단위 | 포함 | 제외 |
|------|------|------|
| **TX-Prepare** | reservation insert `PENDING_PAYMENT`, inventory `hold`, Intent `RequiresAction`+`expires_at`, idempotency row insert | PG 호출 |
| **TX-Finalize** | Intent → `Succeeded`, reservation → `CONFIRMED`, `InventoryApi.confirmHold` | PG 호출 |
| **TX-Expire** | Intent → `Cancelled`, reservation → `EXPIRED`, `releaseHold` | PG |
| **TX-Refund** | refund Intent `Succeeded`, (정산 차감은 집계 시) | PG refund 호출은 TX 밖 |
| **Non-TX** | `PaymentGateway.charge/refund`, 웹훅 HTTP | DB 쓰기 직접 금지 — 결과만 다음 TX에 전달 |

**멱등:**
- `idempotency_record(key PK/UNIQUE, response_json, status, created_at)`
- charge 진입: 키로 조회 → 이미 terminal이면 응답 재사용 → 아니면 in-flight 락(행 잠금 `SELECT … FOR UPDATE`) 후 PG
- PG 이벤트: `pg_event_id` UNIQUE — 중복 웹훅 no-op

**대안 기각:**
- A 채택: Prepare / Gateway / Finalize 분리
- B 기각: 단일 `@Transactional` 안에 PG — 커넥션 점유·부분 커밋 불가
- C 기각: 클라이언트 UUID를 멱등 키로 — 연타마다 새 키 → 이중청구

**아직 안 정한 것:** 실PG 어댑터, 아웃박스 테이블 vs 동기 facade confirm, 자동환불(만료 후 성공)

- [ ] **Step 2: wiki 인덱스에 ADR-003 추가 후 상태 `Accepted`**

- [ ] **Step 3: Commit**

```bash
git add wiki/decisions/003-payment-idempotency-tx.md wiki/README.md
git commit -m "docs(adr): payment idempotency and transaction boundaries"
```

---

### Task 2: PaymentIntent 도메인 상태머신 (순수 Kotlin)

**Files:**
- Create: `modules/booking/src/main/kotlin/co/handys/booking/payment/domain/PaymentIntentStatus.kt`
- Create: `modules/booking/src/main/kotlin/co/handys/booking/payment/domain/PaymentIntent.kt`
- Create: `modules/booking/src/main/kotlin/co/handys/booking/payment/domain/IdempotencyKeys.kt`
- Test: `modules/booking/src/test/kotlin/co/handys/booking/payment/domain/PaymentIntentTest.kt`

**Interfaces:**
- Produces:
  - `fun IdempotencyKeys.chargeFull(reservationId: String): String` → `pay:{id}:CHARGE_FULL`
  - `PaymentIntent.markSucceeded(at): PaymentIntent` — 허용 전이만, 아니면 `IllegalStateException`
  - `PaymentIntent.markCancelled(at): PaymentIntent`
  - statuses: `RequiresAction`, `Processing`, `Succeeded`, `Cancelled`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `Succeeded is terminal - second markSucceeded throws`() {
    val intent = PaymentIntent.create(
        id = "pi_1",
        reservationId = "r1",
        amountWon = 100_000L,
        idempotencyKey = IdempotencyKeys.chargeFull("r1"),
        expiresAt = Instant.parse("2026-09-25T12:15:00Z"),
        now = Instant.parse("2026-09-25T12:00:00Z"),
    ).markProcessing(Instant.parse("2026-09-25T12:01:00Z"))
     .markSucceeded(Instant.parse("2026-09-25T12:02:00Z"))

    assertFailsWith<IllegalStateException> {
        intent.markSucceeded(Instant.parse("2026-09-25T12:03:00Z"))
    }
}

@Test
fun `charge key is business-scoped not random`() {
    assertEquals("pay:r1:CHARGE_FULL", IdempotencyKeys.chargeFull("r1"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-booking:test --tests co.handys.booking.payment.domain.PaymentIntentTest`  
Expected: FAIL (class missing)

- [ ] **Step 3: Minimal domain implementation**

`PaymentIntent`는 data class + 복사 전이. `Processing` → `Succeeded`/`Cancelled`만 허용. `RequiresAction` → `Processing` 허용. `Succeeded`/`Cancelled`에서 추가 전이 금지.

- [ ] **Step 4: Run tests — PASS**

- [ ] **Step 5: Commit**

```bash
git add modules/booking/src/main/kotlin/co/handys/booking/payment/domain \
        modules/booking/src/test/kotlin/co/handys/booking/payment/domain
git commit -m "feat(booking): PaymentIntent state machine and idempotency key format"
```

---

### Task 3: Inventory hold/confirm/release 최소 facade

**Files:**
- Modify: `modules/inventory/src/main/kotlin/co/handys/inventory/api/InventoryApi.kt`
- Create: `modules/inventory/.../application/InMemoryInventoryService.kt` (또는 JPA — 과제 초기면 in-memory + 이후 교체 가능. **테스트에서 결정적**이면 InMemory 먼저)
- Test: `modules/inventory/src/test/.../InventoryHoldTest.kt`
- Modify: `modules/inventory/build.gradle.kts` — spring-context 이미 있으면 OK; test에 junit

**Interfaces:**
- Produces:
```kotlin
interface InventoryApi {
    fun hold(cmd: HoldCommand): HoldResult          // HoldResult(holdId, expiresAt)
    fun confirmHold(holdId: String)
    fun releaseHold(holdId: String)
}
data class HoldCommand(
    val propertyId: String,
    val roomTypeOrUnitId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate, // exclusive
    val mode: SellMode,
    val expiresAt: Instant,
)
```
- double confirm → no-op or throw once — **confirm 후 재confirm은 no-op**
- unknown holdId → throw
- release 후 confirm → throw

- [ ] **Step 1: Failing test — hold then confirm frees sellable semantics**

```kotlin
@Test
fun `confirmHold is idempotent`() {
    val api = InMemoryInventoryService()
    val hold = api.hold(sampleHold())
    api.confirmHold(hold.holdId)
    api.confirmHold(hold.holdId) // must not throw
}
```

- [ ] **Step 2: Run — FAIL**

- [ ] **Step 3: Implement InMemoryInventoryService** (ConcurrentHashMap). 오버북 게이트 풀 구현은 이 Task 범위 밖 — capacity 충분 가정 또는 capacity=1로 이중 hold 거부 정도만.

- [ ] **Step 4: PASS + Commit**

```bash
git commit -m "feat(inventory): minimal hold confirm release API for payment finalize"
```

---

### Task 4: Idempotency store + PaymentIntent JPA (또는 테스트용 인메모리 저장소)

**Files:**
- Create: `modules/booking/.../payment/application/PaymentIntentRepository.kt` (interface)
- Create: `modules/booking/.../payment/application/IdempotencyStore.kt`
- Create: `modules/booking/.../payment/infrastructure/InMemoryPaymentStores.kt` (먼저)
- Test: `.../IdempotencyStoreTest.kt`
- Modify: `modules/booking/build.gradle.kts` — 필요 시 `spring-tx` only; JPA는 Task 10에서 app-api 통합 시 도입해도 됨

**권장 순서 (YAGNI):** 유스케이스까지 **인메모리 저장소**로 완성 → 마지막에 JPA 교체. 멱등 UNIQUE 동작은 인메모리에서도 `putIfAbsent`로 검증.

**Interfaces:**
```kotlin
interface IdempotencyStore {
    /** @return existing if key present, else null after reserving slot */
    fun beginOrGet(key: String): IdempotencyEntry?
    fun complete(key: String, responsePayload: String, terminal: Boolean)
}
data class IdempotencyEntry(val key: String, val payload: String?, val terminal: Boolean)
```

- [ ] **Step 1: Test concurrent begin — second sees first**

```kotlin
@Test
fun `second beginOrGet returns first in-flight entry`() {
    val store = InMemoryIdempotencyStore()
    val a = store.beginOrGet("pay:r1:CHARGE_FULL")
    assertNull(a) // first caller reserved
    val b = store.beginOrGet("pay:r1:CHARGE_FULL")
    assertNotNull(b)
}
```

Clarify API: first call returns `null` meaning “you own the work”; subsequent return entry. Or use sealed `BeginResult.Owned | BeginResult.Existing`. **Use sealed:**

```kotlin
sealed class BeginResult {
    data object Acquired : BeginResult()
    data class Existing(val entry: IdempotencyEntry) : BeginResult()
}
fun begin(key: String): BeginResult
```

- [ ] **Step 2–4: Implement, PASS, Commit**

```bash
git commit -m "feat(booking): idempotency store acquire-or-return semantics"
```

---

### Task 5: MockPaymentGateway (TX 밖 전용)

**Files:**
- Create: `modules/booking/.../payment/application/PaymentGateway.kt`
- Create: `modules/booking/.../payment/infrastructure/MockPaymentGateway.kt`
- Test: `.../MockPaymentGatewayTest.kt`

**Interfaces:**
```kotlin
interface PaymentGateway {
    fun charge(request: ChargeRequest): ChargeResult
}
data class ChargeRequest(
    val idempotencyKey: String,
    val amountWon: Long,
    val reservationId: String,
)
sealed class ChargeResult {
    data class Succeeded(val pgPaymentId: String, val pgEventId: String) : ChargeResult()
    data class Declined(val reason: String) : ChargeResult()
}
```

Mock 동작 (테스트/Admin으로 제어):
- 기본: Succeeded, `pgEventId = "evt_" + UUID` but **같은 idempotencyKey면 같은 pgPaymentId/pgEventId 반환** (게이트웨이 쪽 멱등 시뮬)
- `MockPaymentGateway.nextBehavior = DELAY | DECLINE | SUCCEED` 스레드 로컬/원자 설정

**금지:** Mock 구현이 DB/Repository를 호출하지 않음.

- [ ] **Step 1–5: TDD + Commit**

```bash
git commit -m "feat(booking): MockPaymentGateway with per-key stable success ids"
```

---

### Task 6: CreateDirectReservation — TX-Prepare only

**Files:**
- Create: `modules/booking/.../domain/Reservation.kt` (`PENDING_PAYMENT`, `CONFIRMED`, `EXPIRED`, `CANCELLED`, `paymentSource`)
- Create: `modules/booking/.../payment/application/CreateDirectReservationService.kt`
- Create: `modules/booking/.../payment/application/ReservationRepository.kt` + in-memory
- Test: `CreateDirectReservationServiceTest.kt`

**Interfaces:**
```kotlin
data class CreateDirectReservationCommand(
    val propertyId: String,
    val roomTypeOrUnitId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val amountWon: Long,
    val mode: SellMode,
)
data class CreateDirectReservationResult(
    val reservationId: String,
    val paymentIntentId: String,
    val idempotencyKey: String,
    val expiresAt: Instant,
)
// CreateDirectReservationService.execute(cmd): result
```

**TX-Prepare (단일 `@Transactional` 또는 인메모리 단위 원자성):**
1. `expiresAt = clock.now() + 15.minutes`
2. `inventory.hold(...)`
3. reservation `PENDING_PAYMENT` + `holdId` + `paymentSource=DIRECT`
4. PaymentIntent `RequiresAction`, key=`pay:{id}:CHARGE_FULL`
5. **PG 호출 없음** — 테스트에서 gateway mock verify zero interactions

- [ ] **Step 1: Failing test**

```kotlin
@Test
fun `prepare does not call payment gateway`() {
    service.execute(sampleCmd())
    verify(gateway, never()).charge(any())
    assertEquals(ReservationStatus.PENDING_PAYMENT, reservations.get(id).status)
}
```

- [ ] **Step 2–4: Implement, PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(booking): create direct reservation prepare TX without PG"
```

---

### Task 7: ChargePaymentService — PG outside TX + Finalize

**Files:**
- Create: `modules/booking/.../payment/application/ChargePaymentService.kt`
- Test: `ChargePaymentServiceTest.kt` (연타·이미 Succeeded·Decline)

**Interfaces:**
```kotlin
fun charge(reservationId: String): ChargePaymentResult
// ChargePaymentResult.AlreadySucceeded | JustSucceeded | Declined | Expired
```

**알고리즘 (ADR-003):**

```
1. Load intent by reservationId + CHARGE_FULL. If Cancelled/Expired reservation → error INTENT_EXPIRED
2. If intent.Succeeded → return AlreadySucceeded (NO gateway call)
3. begin(idemKey):
     Existing(terminal) → deserialize & return
     Existing(in-flight) → wait/poll short OR return Processing (MVP: return same in-flight; 연타 테스트는 single-thread 재진입 시 Existing)
     Acquired → continue
4. TX-mark Processing (짧은 TX)  — optional
5. **END TX / no TX:** result = gateway.charge(...)
6. if Declined → TX: intent RequiresAction, complete idem non-terminal or terminal declined payload; return Declined
7. if Succeeded → TX-Finalize:
     intent.markSucceeded
     reservation.CONFIRMED
     inventory.confirmHold(holdId)
     idempotency.complete(key, payload, terminal=true)
8. Never call gateway inside step 7's transaction wrapper
```

테스트로 **TX 경계 강제:**

```kotlin
@Test
fun `five charge calls invoke gateway only once`() {
    repeat(5) { service.charge("r1") }
    verify(gateway, times(1)).charge(any())
}

@Test
fun `succeeded charge confirms inventory`() {
    service.charge("r1")
    assertTrue(inventory.isConfirmed(holdId))
}
```

Gateway mock: `answer`에서 `assertFalse(TransactionSynchronizationManager.isActualTransactionActive())`  
→ Spring 테스트면 `spring-tx` 테스트 의존 추가. 인메모리 단계면 **ChargePaymentService 구조로 gateway를 transactional bean 밖에서 호출**함을 코드 리뷰 체크리스트로 강제하고, IT에서 검증.

구조 강제 패턴:

```kotlin
class ChargePaymentService(
    private val tx: TransactionTemplate, // or separate beans
    private val gateway: PaymentGateway,
) {
    fun charge(reservationId: String): ChargePaymentResult {
        val prepared = tx.execute { markProcessingOrReturnExisting(...) }!!
        if (prepared is ShortCircuit) return prepared.result
        val gw = gateway.charge(prepared.request) // NO tx
        return tx.execute { finalize(prepared, gw) }!!
    }
}
```

- [ ] **Step 1–5: TDD, PASS, Commit**

```bash
git commit -m "feat(booking): charge with idempotent PG call outside DB transaction"
```

---

### Task 8: Webhook 중복 + 만료 후 늦은 성공 → 불일치 큐

**Files:**
- Create: `.../payment/domain/PaymentMismatch.kt` (`LATE_SUCCESS_AFTER_EXPIRE`)
- Create: `.../payment/application/HandlePgWebhookService.kt`
- Create: `.../payment/application/MismatchQueue.kt` (in-memory list)
- Create: `.../payment/infrastructure/PgEventDedupStore.kt` (UNIQUE pg_event_id)
- Test: `HandlePgWebhookServiceTest.kt`

**규칙:**
- 동일 `pgEventId` 3회 → Finalize 1회
- reservation already `EXPIRED` + success webhook → enqueue mismatch, **confirm 금지**

```kotlin
@Test
fun `duplicate webhook finalizes once`() {
    repeat(3) { handler.onSuccess(pgEventId = "evt_1", reservationId = "r1") }
    verify(inventory, times(1)).confirmHold(any())
}

@Test
fun `late success after expire enqueues mismatch`() {
    expire(r1)
    handler.onSuccess("evt_late", "r1")
    assertEquals(0, confirmedCount())
    assertEquals(1, mismatches.size)
}
```

- [ ] **Commit:** `feat(booking): pg webhook dedup and late-success mismatch queue`

---

### Task 9: TTL expire job (15분)

**Files:**
- Create: `ExpirePaymentIntentsService.kt`
- Test: clock 고정 Instant

```kotlin
@Test
fun `expire releases hold and cancels intent`() {
    clock.instant = expiresAt.plusSeconds(1)
    job.runOnce()
    assertEquals(EXPIRED, reservation.status)
    assertEquals(Cancelled, intent.status)
    verify(inventory).releaseHold(holdId)
}
```

- [ ] **Commit:** `feat(booking): expire pending payment intents after 15m TTL`

---

### Task 10: Cancel + refund 이진 (24h)

**Files:**
- Create: `CancelReservationService.kt`
- Create: refund Intent + `IdempotencyKeys.refund(reservationId, cancelEventId)`
- `cancelEventId` = 서버 발급 UUID, 취소 행에 저장 (클라이언트 입력 아님)
- Gateway.refund는 TX 밖 (charge와 동일 패턴). Mock은 즉시 Succeeded.

```kotlin
@Test
fun `cancel 25h before checkin refunds full`() { ... assertEquals(amount, refund.amount) }
@Test
fun `cancel 12h before checkin refunds zero`() { ... assertNull(refundIntent) }
@Test
fun `cancel exactly 24h before checkin refunds zero`() { ... } // 등호 = 0
```

- [ ] **Commit:** `feat(booking): cancel with binary refund window`

---

### Task 11: OTA payout Posted + Owner settlement run

**Files:**
- Create: `PostOtaPayoutService.kt` — key `payout:{channel}:{channelPayoutId}`
- Create: `RunOwnerSettlementService.kt` — key `stl:{propertyId}:{yyyy-MM}:ps-v1`
- Create: settlement line model
- Test: `OwnerSettlementServiceTest.kt`

**집계 규칙 (PRD 수식):**
- DIRECT: `checkout` ∈ month ∧ charge Succeeded ∧ net>0 → line; net = charge − refunds
- OTA: payout Posted ∧ `postedAt` ∈ month
- fee = floor(net * 0.15); owner = net - fee
- 동일 `(reservationId, paymentIntentId)` 두 번 넣히면 run **Failed** (TC-STL-DUP → **Failed만**, 라인1 모호성 제거)
- 동일 stl 키 재실행 → 같은 `runId` 반환

```kotlin
@Test
fun `settlement run is idempotent`() {
    val a = settlement.run("prop1", YearMonth.of(2026, 9))
    val b = settlement.run("prop1", YearMonth.of(2026, 9))
    assertEquals(a.runId, b.runId)
}

@Test
fun `OTA checkout Jan payout Feb appears in February only`() { ... }
```

OTA 예약 생성 헬퍼: Intent 없이 CONFIRMED + `paymentSource=OTA` (채널 stub 최소 함수).

- [ ] **Commit:** `feat(booking): ota payout post and monthly owner settlement`

---

### Task 12: app-api REST + 통합 테스트 (TC-PAY-IDEM / STL)

**Files:**
- Wire Spring `@Configuration` in booking + inventory; JPA 또는 인메모리 `@Profile("dev")` 빈
- Controllers:
  - `POST /guest/reservations` → prepare
  - `POST /guest/reservations/{id}/charge` → charge
  - `POST /webhooks/mock-pg` → webhook
  - `POST /admin/payouts` → OTA posted
  - `POST /admin/settlements` → run
  - `GET /admin/mismatches`
- Test: `app-api/src/test/.../PaymentIdempotencyIT.kt` with `@SpringBootTest` + MockMvc

IT 필수:
- TC-PAY-IDEM-01 연타 charge → gateway 1
- TC-PAY-STATE-01 decline → not CONFIRMED
- TC-PAY-MISMATCH-01 expire then webhook
- TC-STL-IDEM-01

`modules/booking/build.gradle.kts`에 테스트용 mockito 필요 시 추가. `app-api`에 booking/inventory 의존 이미 있는지 확인하고 없으면 추가.

- [ ] **Step: bootJar + test green**

```bash
./gradlew :module-booking:test :module-inventory:test :app-api:test :app-api:bootJar
```

Expected: BUILD SUCCESSFUL

- [ ] **Commit:** `feat(api): payment and settlement HTTP endpoints with idempotency ITs`

---

### Task 13: Wiki 링크 + 스PEC 커버리지 체크리스트

**Files:**
- Modify: `wiki/prd/payment-settlement/prd.md` related → ADR-003
- Modify: `wiki/README.md` if needed
- Create optional: `docs/superpowers/specs/2026-09-25-payment-settlement-design.md` — **스킵 가능** (정책/PRD가 이미 있음). 대신 PRD TC 표를 구현 체크박스로 복사해 이 플랜 하단 Self-review에 체크.

- [ ] **Commit:** `docs: link ADR-003 from payment-settlement PRD`

---

## Self-review (작성 시 점검)

| PRD/정책 요구 | Task |
|---------------|------|
| 멱등 키 포맷·클라이언트 키 금지 | 1, 2, 7 |
| PG를 TX 안에 넣지 않음 | 1, 5, 7 |
| 성공 시에만 confirmed | 7, 8 |
| silent confirm 금지·불일치 큐 | 8 |
| held TTL 15분 | 6, 9 |
| OTA CHARGE 없음 | 11 |
| 정산 recognition·stl 멱등·중복 Failed | 11 |
| 환불 24h 이진·등호=0 | 10 |
| Mock PG·연타 TC | 5, 7, 12 |
| ADR-002 정산 모듈 안 만듦 | 11 (booking 내) |

Placeholder scan: 없음.  
타입명: `ChargePaymentService.charge`, `IdempotencyKeys.chargeFull`, `InventoryApi.confirmHold` 전 Task 일치.

---

## 리뷰어 체크리스트 (매 Task)

- [ ] 새 코드 경로에 `@Transactional` + gateway 호출이 같은 메서드에 있는가? → **거부**
- [ ] 멱등 키가 UUID.random 인가? → **거부**
- [ ] 다이렉트 CONFIRMED가 inventory confirm 없이 끝나는가? → **거부**
