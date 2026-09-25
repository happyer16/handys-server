---
title: "결제·정산·멱등성"
type: prd
status: active
owner: "handys-assignment"
created: "2026-09-25"
updated: "2026-09-25"
related:
  - ../../decisions/003-payment-idempotency-tx.md
  - policy.md
  - ../../decisions/001-inventory-overbooking.md
  - ../../decisions/002-module-boundaries.md
  - ../cms-checkin-core/prd.md
---

# 결제·정산·멱등성

## 관련 문서

- 정책서: `policy.md` (이 PRD의 What/Why 원본. 충돌 시 정책서가 이긴다)
- ADR: [ADR-001 재고·오버부킹](../../decisions/001-inventory-overbooking.md) · [ADR-002 모듈 경계](../../decisions/002-module-boundaries.md)
- 인접: [CMS·체크인 PRD](../cms-checkin-core/prd.md) — 기존 Guest “결제 완료” stub는 **본 PRD의 다이렉트 Intent 플로우로 대체**
- 디자인: 없음 (과제 프로토타입 — 와이어 수준)

---

## 1. 기능 개요

### 배경

예약만 있고 결제·정산 Intent가 없으면 `held`가 남거나 재시도로 이중청구가 나고, 오너 월 정산에 같은 숙박이 두 번 잡힌다. 다이렉트는 우리가 수금하고 OTA는 채널이 수금하므로 **인식 시점**을 섞으면 장부가 틀린다.

### 해결하고자 하는 것

게스트가 다이렉트 예약 시 **이중청구·미결제 확정 없이** 한 번만 결제되고, 오너가 월말에 **같은 숙박을 두 번 세지 않은** 정산액을 받는다.

**가설:** Intent 멱등 키 + `결제 성공 시에만 confirmed` + 오너 recognition 규칙을 고정하면, 재시도·웹훅 중복에서도 이중청구·이중정산이 0이 된다.

### 스콥

**PRD에서 닫는 가정 (정책 §8–9):**

| 항목 | 확정 값 |
|------|---------|
| 통화 | KRW, 정수 원 |
| held TTL / Intent 만료 | **15분** (지점 TZ 기준 절대시각 `expires_at`) |
| 플랫폼 수수료 (오너 정산) | 다이렉트·OTA 공통 **고정 15%** (`owner_payout = net_recognized × 0.85`) |
| 체크인 전 취소 환불 | 체크인 **24시간 초과 전** 취소 → 전액 환불 Intent. **24시간 이내** → 환불 0 (노쇼와 동일하게 결제 유지). 부분 환불 % 티어는 MVP 없음 |
| PG | **모의 PG** (`MockPg`). 실카드 없음 |
| OTA payout | Admin이 `channel_payout_id`로 **입금 Posted** 수동 stub (파일 ingest 없음) |
| 정산 policy_version | `"ps-v1"` 문자열 상수 |
| 불일치(만료 후 성공 웹훅) | **불일치 큐**에만 적재. silent confirmed 금지. 자동환불은 2차 |

**인접 의존:**

| 인접 | 관계 |
|------|------|
| ADR-001 재고 | 다이렉트: Intent Succeeded → inventory `held→confirmed`. OTA: 채널 확정 시 confirmed (결제 Intent 없음) |
| CMS·체크인 | readiness의 `payment_ok` = 다이렉트면 Intent Succeeded, OTA면 채널 CONFIRMED |
| 추가결제(주차 등) | 스키마·멱등 규칙만 정의. UI/상품은 스콥 아웃(정책 안 함과 정합) |

**스콥 인:**

| 지면/기능 | 설명 |
|-----------|------|
| Guest · 다이렉트 결제 | 예약 생성 → held + PENDING_PAYMENT → 결제 UI → Intent CHARGE_FULL |
| Mock PG · charge/webhook | 성공/실패/지연/중복 웹훅 시뮬 |
| Admin · 불일치 큐 | TTL 만료 후 늦은 성공 등 |
| Admin · OTA 입금 stub | payout Posted |
| Admin · 오너 월 정산 | property × yyyy-MM 런 생성·조회·라인 |
| API · 멱등 | 청구/환불/payout/settlement_run 키 |
| TC · 실패 주입 | 정책 §G 매트릭스 |

**스콥 아웃:**

| 제외 항목 | 제외 사유 |
|-----------|-----------|
| 실 PG·카드 심사 | 정책 §7 |
| 보증금/잔금 분할·체크인 직전 결제 | 정책 §7 |
| 전 OTA 정산 파일 자동 대사 | 정책 §7 — stub Posted만 |
| 오너 실시간 정산·중도 인출 | 월 단위만 |
| 포인트·쿠폰·다통화·세무 | YAGNI |
| 추가결제 상품 UI | 규칙만, 상품은 TODO-PARK 등과 함께 |
| 부분 환불 티어·채널별 환불 파편 통일 | 체크인 24h 이진만 |
| 자동환불(만료 후 성공) | 불일치 큐만 |

---

## 2. 목표 및 성공 지표

| 구분 | 지표 | 목표 | 측정 방법 |
|------|------|------|-----------|
| 핵심 | 동일 Intent 재시도 시 PG charge 호출 수 | **= 1** | TC-PAY-IDEM-* |
| 핵심 | 결제 실패인데 CONFIRMED | **0건** | TC-PAY-STATE-* |
| 핵심 | 정산 런 내 reservation_id(+intent) 중복 | **0건** | TC-STL-DUP-* |
| 모니터링 | 불일치 큐 적재 건수 | 과제: 분포만. 데모에서 ≥1 시나리오 재현 | `payment_mismatch_enqueued` |
| 가드레일 | silent confirmed (만료 후 웹훅으로 조용히 CONFIRMED) | **0** | TC-PAY-MISMATCH-01 |

### 실패 판단 기준

- 재시도 N회에서 charge ≥2 → 실패 → 키 범위·상태머신 재설계
- 결제 없이 CONFIRMED → 실패 → inventory 전이 가드 수정
- 정산 중복 라인 → 실패 → settlement_run_key / 라인 유니크 수정  
UI 꾸미기로 덮지 않음 (정책 §4·§6).

---

## 3. 사용자 시나리오

### 3.1 게스트 — 다이렉트 결제 성공

1. 게스트가 다이렉트 예약을 제출한다.
2. 시스템이 재고 `held`, 예약 `PENDING_PAYMENT`, Intent(`CHARGE_FULL`) `Processing`, `expires_at=now+15m`을 만든다. **결제 화면**에 금액·만료 카운트다운을 표시한다.
3. 게스트가 **결제하기**를 누른다 (연타 가능).
4. 시스템이 동일 idempotency key로 Mock PG charge를 **최대 1회** 수행한다.
5. PG 성공 웹훅(또는 동기 성공) 시 Intent `Succeeded`, 예약 `CONFIRMED`, 재고 `confirmed`. 화면에 **예약 확정·영수증 1건**을 표시한다.

### 3.2 게스트 — 결제 실패 후 재시도

1. PG가 거절한다 → Intent `RequiresAction`(또는 `Failed` 후 동일 키로 재시도 허용 상태는 ADR 후보; **MVP: `RequiresAction` 유지, confirmed 금지**).
2. 시스템이 “결제 실패. TTL 내 재시도”를 표시한다. 예약은 `PENDING_PAYMENT`.
3. 게스트가 다시 결제하기 → 같은 키로 charge. 성공 시 3.1-5와 동일.

### 3.3 게스트 — TTL 만료

1. `expires_at` 경과.
2. 시스템이 held 해제, 예약 `EXPIRED`, Intent `Cancelled`. 화면에 “시간 만료, 다시 예약”을 표시한다.
3. 이후 같은 reservation으로 charge 요청 → **400 `INTENT_EXPIRED`**.

### 3.4 운영 — 웹훅 중복·지연

1. Mock PG가 동일 `pg_event_id` 성공 웹훅을 3회 보낸다.
2. 시스템은 Intent 전이를 **1회만** 수행하고 이후 ack만.
3. (변형) 예약이 이미 `EXPIRED`인데 성공 웹훅이 오면 CONFIRMED로 올리지 않고 **불일치 큐**에 `LATE_SUCCESS_AFTER_EXPIRE`를 넣고 Admin에 표시한다.

### 3.5 게스트 — 체크인 전 취소·환불

1. 게스트/Admin이 취소한다.
2. 시스템이 재고 복구, 예약 `CANCELLED`.
3. `now < checkin_at - 24h`이면 환불 Intent(`REFUND`) 생성·멱등 성공 → 전액. 아니면 환불 Intent 없음(금액 유지).
4. 화면에 환불 예정/불가 사유를 표시한다.

### 3.6 운영 — OTA 예약 (결제 Intent 없음)

1. 채널 stub이 OTA 예약을 확정 push한다.
2. 시스템이 ADR-001로 confirmed. **CHARGE Intent를 만들지 않는다.** `payment_source=OTA`.
3. Admin 예약 상세에 “채널 수금 · 입금 대기/Posted”를 표시한다.

### 3.7 운영 — OTA 입금 Posted

1. Admin이 `channel_payout_id`, 금액, 대상 reservation(들)을 입력하고 Posted한다.
2. 동일 `channel + channel_payout_id` 재제출 → 기존 Posted 결과 반환(멱등).
3. 화면에 입금 상태 Posted를 표시한다.

### 3.8 오너/운영 — 월 정산 런

1. Admin이 property + `yyyy-MM`으로 정산 생성(또는 재실행)한다.
2. 시스템이 `settlement_run_key`로 멱등 집계한다. 라인: 다이렉트(체크아웃 월) + OTA(Posted 입금 월) − 환불 조정.
3. 화면에 합계·라인 목록·`owner_payout`을 표시한다. 동일 키 재실행 시 **같은 run_id·같은 라인**을 보여 준다.

---

## 4. 정책 및 비즈니스 로직

### 4.1 PaymentIntent

#### 상태

`RequiresAction` | `Processing` | `Succeeded` | `Cancelled` | `Failed`(거절 후 종료 — MVP에서는 RequiresAction으로 재시도 유도 가능하면 Failed 미사용 가능)

**허용 전이:**  
RequiresAction/Processing → Succeeded | Cancelled  
RequiresAction → Processing (charge 시작)  
Succeeded / Cancelled → (최종, 전이 없음; 웹훅은 no-op)

#### 멱등 키 (저장·전송 문자열)

```
pay:{reservation_id}:CHARGE_FULL
pay:{reservation_id}:{purpose}:{order_line_id}   # 추가결제 — UI 없음, API 계약만
ref:{reservation_id}:{cancel_event_id}
payout:{channel}:{channel_payout_id}
stl:{property_id}:{yyyy-MM}:ps-v1
```

클라이언트는 키를 **생성하지 않는다**. 서버가 reservation 생성 시 Intent와 키를 고정한다.

#### 데이터 → UI

| 수집 값 | Guest 표시 |
|---------|------------|
| PENDING_PAYMENT + expires_at | “결제 대기 · m분 s초 남음” + 금액 |
| Succeeded | “예약 확정” + 영수증(금액 1줄) |
| EXPIRED / Cancelled | “결제 시간 만료” |
| RequiresAction (거절) | “결제 실패 · 다시 시도” |
| OTA CONFIRMED | “예약 확정 (채널 결제)” — 결제 버튼 없음 |

| 수집 값 | Admin 표시 |
|---------|------------|
| mismatch queue row | reason_code, reservation_id, pg_event_id, created_at |
| payout Pending | “입금 대기” |
| payout Posted | “Posted · {amount}” |
| settlement run | period, gross, fees, owner_payout, lines[] |

#### 계산식

```
# 다이렉트 주결제
charge_amount = list_price_total   # CMS PRD list_price × nights (반개구간 night 수)

nights = checkout_date - checkin_date   # 일수, ≥1

# 환불 (체크인 전 취소)
refund_amount =
  if reservation.status was CONFIRMED
     and cancel_at < checkin_at - 24h
     and charge Intent Succeeded
  then charge_amount
  else 0

# 오너 정산 라인 (다이렉트)
include_direct_line =
  checkout_date ∈ period_month
  ∧ charge Intent Succeeded
  ∧ (charge_amount - sum(refund Succeeded on this reservation)) > 0

net_direct = charge_amount - refunds_succeeded_amount

# 오너 정산 라인 (OTA)
include_ota_line =
  payout.status == Posted
  ∧ payout.posted_at ∈ period_month   # 입금 월
  ∧ 해당 payout에 매핑된 reservation 분배액

# 수수료·지급
fee = floor(recognized_net * 0.15)
owner_payout_line = recognized_net - fee
owner_payout_run = sum(owner_payout_line)

# 정산 런 유일성
assert unique (reservation_id, payment_intent_id) per run
# 위반 시 run Failed, 지급 지시 생성 금지
```

금액 표시: `₩` + 천단위 콤마. null 금액 → `—` (0원과 구분).

### 4.2 재고·예약 연동 (ADR-001)

```
on Intent Succeeded (CHARGE_FULL, direct):
  reservation → CONFIRMED
  inventory held → confirmed   # 원자적(같은 트랜잭션 또는 outbox 순서 보장 — ADR)

on Intent Cancelled / TTL:
  reservation → EXPIRED (if still PENDING_PAYMENT)
  release held

on OTA channel confirm:
  reservation → CONFIRMED, payment_source=OTA
  # no PaymentIntent CHARGE
```

결제 없이 `CONFIRMED` API/어드민 강제 경로 **없음** (다이렉트).

### 4.3 정산 런

- 입력: `property_id`, `period` (`yyyy-MM`)
- 키: `stl:{property_id}:{period}:ps-v1`
- 재실행: 기존 run이 `Succeeded`면 재계산 없이 반환. `Failed`면 수정 후 재실행 허용(같은 키 덮어쓰기 — 라인 전체 교체, 감사 로그 append)

---

## 5. 상태 의존성 매트릭스

| 트리거 | 갱신 대상 | 갱신 범위 | 비고 |
|--------|----------|----------|------|
| 예약 생성(direct) | reservation, inventory held, Intent | 해당 reservation | 키 고정 |
| 결제하기 클릭 | Intent Processing → PG | 동일 키 | 연타 no-op after first in-flight |
| PG 성공 웹훅 | Intent Succeeded, CONFIRMED, confirmed 재고 | 1회 전이 | 중복 ack |
| PG 실패 | Intent RequiresAction | confirmed 금지 | |
| TTL job | EXPIRED, held 해제, Intent Cancelled | | |
| 늦은 성공 웹훅 + EXPIRED | mismatch queue만 | CONFIRMED 금지 | |
| 취소 | CANCELLED, 재고 복구, 환불 Intent? | 24h 규칙 | |
| OTA payout Posted | payout row | 정산 포함 가능 | 멱등 |
| 정산 런 실행 | settlement_run + lines | property×month | 멱등 |

### 상태 조합 매트릭스

| payment_source | Intent CHARGE | 예약 CONFIRMED 가능 조건 | Guest 결제 버튼 |
|----------------|---------------|---------------------------|-----------------|
| DIRECT | 필수 | Intent Succeeded | TTL 내 PENDING만 |
| OTA | 없음 | 채널 확정 | 숨김 |

| 예약 상태 | Intent | 재고 |
|-----------|--------|------|
| PENDING_PAYMENT | Processing/RequiresAction | held |
| CONFIRMED | Succeeded (direct) / — (OTA) | confirmed |
| EXPIRED | Cancelled | 없음 |
| CANCELLED | Succeeded 유지 + 환불 Intent 가능 | 없음 |

---

## 6. 엣지 케이스 및 예외 처리

### 경계값 표시 규칙

| 상황 | 처리 방식 | 적용 지면 |
|------|----------|----------|
| amount = 0 | 결제 버튼 비활성, `INVALID_AMOUNT` | Guest |
| amount null | `—`, 진행 불가 | 전 지면 |
| nights < 1 | 예약 생성 거부 | API |
| TTL 남은 초 = 0 | EXPIRED 처리와 동일 | Guest·job |
| 정산 라인 0건 | run Succeeded, payout 0, “해당 월 정산 대상 없음” | Admin |
| 중복 reservation in run | run Failed | Admin 에러 |

### 데이터 없음 처리 (Nullable 원칙)

| 항목 | 데이터 없을 때 |
|------|--------------|
| Intent (OTA) | 정상. “채널 수금” 표시 |
| payout | “입금 대기” |
| mismatch queue | 빈 목록 “불일치 없음” |
| refund Intent | 환불 0인 취소 — 행 없음, “환불 없음(임박 취소)” |
| owner_payout | 0이면 `₩0` (null 아님) |

### 엣지 조합 (필수 TC)

| ID | 시나리오 | 기대 |
|----|----------|------|
| TC-PAY-IDEM-01 | 결제 5연타 | charge 1, Succeeded 1 |
| TC-PAY-IDEM-02 | 성공 응답 유실 후 재요청 | charge 1, CONFIRMED 1 |
| TC-PAY-IDEM-03 | 웹훅 3중복 | 전이 1 |
| TC-PAY-STATE-01 | PG 거절 | CONFIRMED 0 |
| TC-PAY-MISMATCH-01 | TTL 후 성공 웹훅 | CONFIRMED 0, queue 1 |
| TC-PAY-OTA-01 | OTA 예약 | CHARGE Intent 0건 |
| TC-REF-01 | 체크인 25h 전 취소 | refund = charge |
| TC-REF-02 | 체크인 12h 전 취소 | refund = 0 |
| TC-STL-DUP-01 | 같은 reservation 두 번 집계 시도 | run Failed 또는 라인 1만 |
| TC-STL-IDEM-01 | 동일 stl 키 2회 | run_id 동일, payout 지시 1 |
| TC-STL-OTA-01 | 체크아웃 1월·입금 2월 | 2월 런에만 OTA 라인 |

---

## 7. 이벤트 설계

| 이벤트 | 트리거 | 속성 |
|--------|--------|------|
| `payment_intent_created` | 다이렉트 예약 생성 | reservation_id, amount, expires_at |
| `payment_charge_attempted` | PG charge 호출 직전 | intent_id, idem_key |
| `payment_intent_succeeded` | Succeeded 전이 | reservation_id, amount |
| `payment_intent_cancelled` | TTL/만료 | reservation_id |
| `payment_mismatch_enqueued` | 늦은 성공 등 | reason_code, reservation_id |
| `refund_intent_succeeded` | 환불 성공 | reservation_id, refund_amount |
| `ota_payout_posted` | Admin Posted | channel, channel_payout_id, amount |
| `settlement_run_succeeded` | 정산 성공 | property_id, period, owner_payout, line_count |
| `settlement_run_failed` | 중복 등 | property_id, period, error_code |

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|------|------|----------|
| 2026-09-25 | v1 | 초안. 정책 v1 반영 |
