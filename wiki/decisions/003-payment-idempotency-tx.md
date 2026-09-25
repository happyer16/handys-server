# ADR-003: 결제 멱등성 · 트랜잭션 단위

- **상태:** Accepted
- **날짜:** 2026-09-25
- **맥락:** 다이렉트 결제는 PG 왕복(초 단위·타임아웃)과 DB 전이(Intent·예약·재고·멱등 기록)가 섞인다. 어디까지가 한 트랜잭션이고 재시도·중복 웹훅에 어떻게 응답하는지 고정하지 않으면 이중청구·미결제 확정이 난다.

## 결정

**PG 호출은 어떤 트랜잭션 경계에도 들어가지 않는다.** 한 번의 결제는 짧은 DB 트랜잭션 → PG 호출 → 짧은 DB 트랜잭션으로 쪼갠다. 구간 사이는 **커밋된 상태**로만 이어진다.

### 트랜잭션 단위

| 단위 | 포함 | 제외 |
|------|------|------|
| **TX-Prepare** | reservation insert `PENDING_PAYMENT`, inventory `hold`, Intent `RequiresAction`+`expires_at`, idempotency row insert | PG 호출 |
| **TX-Finalize** | Intent → `Succeeded`, reservation → `CONFIRMED`, `InventoryApi.confirmHold` | PG 호출 |
| **TX-Expire** | Intent → `Cancelled`, reservation → `EXPIRED`, `releaseHold` | PG |
| **TX-Refund** | refund Intent `Succeeded`, (정산 차감은 집계 시) | PG refund 호출은 TX 밖 |
| **Non-TX** | `PaymentGateway.charge/refund`, 웹훅 HTTP | DB 쓰기 직접 금지 — 결과만 다음 TX에 전달 |

**게이트**

- `@Transactional` 메서드 안에서 `PaymentGateway`를 호출하면 위반이다. 오케스트레이션은 TX 밖 application 레이어가 한다.
- TX-Finalize는 Intent·예약·재고를 **한 커밋**에 묶는다. 결제 성공인데 재고가 `held`로 남는 조합을 만들지 않는다.
- Non-TX 구간에서 DB에 직접 쓰지 않는다. PG 결과는 반환값/웹훅 페이로드로 다음 TX에 넘긴다.

### 멱등

`idempotency_record(key PK/UNIQUE, response_json, status, created_at)`

| 컬럼 | 역할 |
|------|------|
| `key` | PK/UNIQUE. 비즈니스 Intent에 고정된 문자열 (PRD §4.1: `pay:{reservation_id}:CHARGE_FULL`, `ref:…`, `payout:…`, `stl:…`) |
| `status` | in-flight / terminal(성공·취소) 구분 |
| `response_json` | terminal일 때 재사용할 응답 본문 |
| `created_at` | 감사·TTL 조회 |

*가정:* 물리 테이블명은 ADR-002 prefix 규칙에 따라 `booking_idempotency_record`. 키는 **서버가** TX-Prepare에서 고정하고 클라이언트는 생성하지 않는다.

**charge 진입 게이트**

```
charge(reservation_id):
  key = "pay:{reservation_id}:CHARGE_FULL"

  # --- 짧은 TX ---
  rec = SELECT * FROM idempotency_record WHERE key = :key FOR UPDATE   # 행 잠금
  if rec.status is terminal:
      return rec.response_json          # 재청구 금지 · 동일 응답 재사용
  rec.status = IN_FLIGHT                # 커밋 → 경계 밖 재진입 차단
  # --- TX 커밋 ---

  result = PaymentGateway.charge(...)   # Non-TX

  # --- TX-Finalize ---
  Intent → Succeeded; reservation → CONFIRMED; InventoryApi.confirmHold
  rec.status = terminal; rec.response_json = result
```

- 연타·재시도는 같은 키로 들어오므로 첫 호출만 PG에 나간다. in-flight 재진입은 “진행 중” 응답을 받는다.
- **PG 이벤트:** `pg_event_id` UNIQUE — 중복 웹훅은 no-op. 이미 terminal인 Intent에는 ack만 하고 전이하지 않는다.
- 예약이 이미 `EXPIRED`인데 성공 웹훅이 오면 `CONFIRMED`로 올리지 않고 불일치 큐에 넣는다 (PRD `LATE_SUCCESS_AFTER_EXPIRE`). silent confirmed 금지.

## 대안과 기각

| 옵션 | 요약 | 결과 |
|------|------|------|
| A (채택) | Prepare / Gateway / Finalize 분리 | 채택 |
| B | 단일 `@Transactional` 안에 PG | 기각 — 커넥션 점유·부분 커밋 불가 |
| C | 클라이언트 UUID를 멱등 키로 | 기각 — 연타마다 새 키 → 이중청구 |

## 왜 이렇게 하나

- 실패 비용이 **돈**이다. 정책서 North Star가 “이중청구·이중정산 0 · 결제 성공↔confirmed 불일치 0”이므로 재시도 경로를 문장이 아니라 키·락·유니크 제약으로 막아야 한다.
- B는 PG 지연만큼 DB 커넥션을 잡아 풀을 고갈시키고, 더 나쁘게는 **롤백해도 PG 측 청구는 남는다**. DB와 외부 시스템은 한 원자 단위가 될 수 없다 — 그래서 “부분 커밋 불가”가 곧 기각 사유다.
- C는 탭·재진입마다 새 키가 발급되어 멱등성이 사라진다. 정책서 §9-D “키는 비즈니스 Intent에 고정” 금지 조항과 정면 충돌.
- ADR-001의 `held→confirmed`는 결제 성공과 같은 커밋(TX-Finalize)에서만 일어난다. 결제 없이 confirmed 되는 경로를 남기지 않는다.
- ADR-002 정합: Intent 상태머신은 `module-booking`, 재고 전환은 `InventoryApi` 포트 호출. `module-inventory`는 결제를 모른다.

## 아직 안 정한 것

- [ ] 실 PG 어댑터 — 프로바이더·연동 시점 (현재 `MockPg`)
- [ ] 아웃박스 테이블 vs 동기 facade confirm — TX-Finalize 순서 보장 방식
- [ ] 자동환불 (TTL 만료 후 늦은 성공) — 현재는 불일치 큐만, 자동 환불 Intent는 2차
- [ ] `idempotency_record` 보관 기간·정리 주기
- [ ] in-flight 가 비정상 종료로 남았을 때 회수(스테일 락) 기준

## 결과 (구현에 미치는 영향)

- `PaymentGateway` 인터페이스는 TX 밖에서만 호출된다. `@Transactional` 메서드에 게이트웨이를 주입해 호출하는 코드는 리뷰에서 반려.
- 테이블 추가: `booking_idempotency_record`(key UNIQUE), PG 이벤트 테이블에 `pg_event_id` UNIQUE 제약.
- charge/refund/payout/settlement 진입점은 모두 같은 게이트(키 조회 → terminal 재사용 → in-flight 락)를 공유한다.
- 웹훅 핸들러는 전이 가능 여부만 판단한다. terminal Intent·중복 `pg_event_id`는 200 ack + no-op.
- 만료 후 성공은 `CONFIRMED` 대신 불일치 큐 적재. 어드민 노출 경로가 필요하다.
- 검증 대상 TC: `TC-PAY-IDEM-01/02/03`, `TC-PAY-STATE-01`, `TC-PAY-MISMATCH-01`.

## 참고

- 관련 ADR: [ADR-001 재고·오버부킹](./001-inventory-overbooking.md) · [ADR-002 서버 모듈 경계](./002-module-boundaries.md)
- 관련 PRD (`wiki/prd/…`): [결제·정산·멱등성 정책서](../prd/payment-settlement/policy.md) · [PRD](../prd/payment-settlement/prd.md)
- 외부/업계: Stripe idempotency key(요청 키 + 저장된 응답 재사용), transactional outbox — 외부 호출을 DB 트랜잭션에서 분리하는 표준 패턴
