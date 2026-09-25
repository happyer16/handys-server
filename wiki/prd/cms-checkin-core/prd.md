---
title: "CMS·체크인 운영 코어"
type: prd
status: active
owner: "handys-assignment"
created: "2026-09-25"
updated: "2026-09-25"
related:
  - policy.md
  - ../../decisions/001-inventory-overbooking.md
---

# CMS·체크인 운영 코어

## 관련 문서

- 정책서: `policy.md` (이 PRD의 What/Why 원본. 충돌 시 정책서가 이긴다)
- ADR: [ADR-001 재고·오버부킹](../../decisions/001-inventory-overbooking.md)
- 제품 컨텍스트: [product-context.md](../../product-context.md)
- 디자인: 없음 (과제 프로토타입 — 와이어 수준 UI면 충분)

---

## 1. 기능 개요

### 배경

무인·앱 숙박에서 **채널에 팔린 재고**와 **입실 가능 상태**가 끊기면 오버부킹·키 미발급 사고가 난다. Plott OS 기준 **01 CMS**와 **04 스마트 체크인**(+ PMS 재고 코어)이 운영 최소축이다. 스마트 프라이싱·주차 부가는 후순위 TODO.

### 해결하고자 하는 것

운영자·시스템이 채널 재고를 **ADR-001** 규칙으로 막은 뒤, 게스트가 체크인 시각에 **키·본인확인·객실 Ready가 모두 Go**인 상태로 입실에 성공한다.

**가설:** 판매 가능 재고(CMS·ADR)와 입실 readiness를 같은 Go/No-Go로 묶으면, 오버북·키 실패 시나리오를 프로토타입에서 재현 전에 차단할 수 있다.

### 스콥

**설정 단위 (PRD 가정 — ADR 미결 닫음):**  
게이트·capacity·mode·`list_price`는 **룸타입(또는 특정방이면 유닛)** 단위. 지점은 부모 컨테이너만. 전역 기본값 없음(시드 데이터로 룸타입마다 채움).

**모드 변경:** 해당 룸타입/유닛에 `CONFIRMED` 또는 `HELD` 예약이 **1건이라도 있으면 모드 변경 금지** (API 400 `MODE_LOCKED`). 데모용 “경고 후 강제” 없음.

**채널 stub 계약:** 채널 id `direct` | `ota_a` | `ota_b` (N≤3). 모두 동일 중앙 API. 채널별 재고 캐시는 `last_sync_at`만 보유, 수량은 캐시하지 않고 매 요청 pull.

**인접 의존 (이번 스콥에서 읽지 않음):**

| 인접 | 관계 |
|------|------|
| HK SLA(#3) 풀플로우 | Ready/Dirty **상태값만** 사용. SLA 타이머·배정 최적화 없음 |
| 취소·환불(#6) | Guest/Admin **취소 버튼 → CANCELLED + sold↓** 만. 환불·채널 파편 없음 |
| 얼리CI / 주차 | 스콥 아웃. 체크인창 고정 15:00 |

**CMS sync 주체:** Admin “동기화 시뮬” 버튼 또는 채널 stub “pull” 호출 성공 시 `last_sync_at=now`. 백그라운드 타이머 없음(과제). TTL 초과 시 stale.

**숙박 구간 표기 (전 지면 동일):** night 집합 = 반개구간 `[checkin_date, checkout_date)`. `occupied` = 다른 CONFIRMED가 동일 유닛에서 이 구간과 교집합.

**타임존:** 모든 `local_time`·`today`·`days_until`은 **지점 `timezone`**(IANA, 시드 `Asia/Seoul`). UTC 저장·표시만 지점 TZ.

**동시성 확정:** 룸타입(또는 유닛)×영향 night 구간에 **DB row lock (SELECT … FOR UPDATE)** 후 accept. 낙관적 버전 미사용.

**스콥 인:**

| 지면/기능 | 설명 |
|-----------|------|
| Admin · 룸타입/유닛 설정 | mode, 게이트 3값, capacity, list_price, stale TTL(지점 공통 가정 OK) |
| Admin · 재고 캘린더 | 날짜 네비(±7일), 룸타입 필터, `available`/`sold`/`max_sellable`/`gate_ok` 컬럼 |
| Admin · 예약 목록 | 상태 필터(CONFIRMED/CANCELLED), reject 로그(reason_code) |
| CMS stub (≤3 채널) | pull 노출 · push 예약 요청. 로컬 임의 증감 금지 |
| Guest · 예약 상세 | readiness 체크리스트 + 첫 No-Go CTA |
| Guest · 결제 stub | “결제 완료” 버튼 1회 → `payment_status=PAID` (`payment_ok=Go`) |
| Guest · 본인확인 stub | “인증 완료” 버튼 → `identity_verified=Go` |
| Guest · 체크인·키 | `checkin_eligible` ∧ all_go 일 때만 키 발급 |
| Admin · HK | Dirty/Cleaning/Ready. Ready만 배정 후보 |
| Admin · 호텔형 배정 | 체크인 시 Ready 유닛 배정 |
| 요금 | 수동/고정 `list_price`만 |

**스콥 아웃:**

| 제외 항목 | 제외 사유 |
|-----------|-----------|
| **TODO-PRICE** 스마트 프라이싱 2.0 | 정책 §10 |
| **TODO-PARK** 주차·결제 부가 | 정책 §10 |
| 실 OTA API / 실 도어락 | stub |
| MLOps·오너스 풀정산·풀 환불 통일 | 정책 아웃 |
| 업셀/이동/보상 풀플로우 | MVP는 판매 거부·키 차단만 |
| held TTL·부분 결제·다통화 | Confirmed/취소만. held=0 |
| force-key / 비인증 flag 강제 | 본인확인 override만 허용 |

---

## 2. 목표 및 성공 지표

| 구분 | 지표 | 목표 | 측정 방법 |
|------|------|------|-----------|
| 핵심 | 게이트 위반 판매 | **0건** | TC-OB-* 전부 pass |
| 핵심 | No-Go 키 발급 | **0건** | TC-CI-BLOCK-* pass |
| 핵심 | All-Go 체크인 성공 | **≥1** | TC-CI-OK-01 pass |
| 모니터링 | No-Go flag 분포 | 과제: **분포만 기록**(수치 목표 없음). 동일 flag가 연속 데모 3회 전부 No-Go면 “항목 과다” 신호 | `readiness_evaluated` |
| 가드레일 | No-Go인데 키 200 | **0** | 서버 가드 + TC |

### 실험 설계 (정책 §6 동기화)

- **기간:** 프로토타입 데모 1회 세트(위 TC 전부) = 1 실험.
- **Hypothesis true:** TC 전 pass → 구현 유지 → HK SLA(#3) 얇게 검토.
- **Hypothesis false 분기:**
  - (A) 키 차단은 맞는데 All-Go 도달이 과도하게 어려움 → **readiness 항목 축소** 후보(`payment_ok` stub 자동 PAID 등) 재실험.
  - (B) 게이트/모드 때문에 정상 판매까지 거절 → **ADR 모드·게이트 기본값** 재검증(overbook_rate=0이 데모 의도와 맞는지).
  - (C) 둘 다 아니면 UI 축소. **프라이싱·주차로 확장하지 않음.**

### 실패 판단 기준

게이트 없이 초과판매 가능 **또는** No-Go인데 키 발급 **또는** All-Go인데 체크인 플로우 없음 → 실패 → 위 false 분기.

---

## 3. 사용자 시나리오

### 3.1 호텔형 · 정상 판매 → All-Go 체크인

1. Admin: 룸타입 `mode=HOTEL_POOL`, `capacity=10`, 게이트 설정, `list_price` 입력.
2. Admin 재고 캘린더에서 D+5 선택 → `max_sellable`/`available` 확인.
3. 채널 stub 재고 pull → 동일 숫자 표시.
4. 예약 요청 1건 → accept → CONFIRMED, `room_unit=null`.
5. Guest 예약 상세: 결제 stub “결제 완료” → `payment_ok`. 본인확인 → `identity_verified`.
6. Admin HK: 유닛 Ready.
7. Guest: `checkin_eligible` 충족 후 “체크인 시작” → 배정 성공 → all_go → “키 받기” → 키 stub 표시.

### 3.2 호텔형 · 오버북 게이트 위반 → 판매 거부

1. sold=capacity, 게이트 미충족(D+1 또는 rate=0).
2. 예약 요청 → reject + `reason_code` (채널·Admin·로그 **동일 코드·동일 문구 키**).

### 3.3 특정방 · 이중 판매 거부

겹치는 유닛 예약 → `UNIT_UNAVAILABLE`.

### 3.4 No-Go · Dirty → 키 차단

배정 유닛 Dirty → 키 deny `READINESS_NOT_MET` / flag `room_ready`.

### 3.5 No-Go · 미인증 → 키 차단

`identity_verified=false` → CTA “본인확인하기”.

### 3.6 CMS stale → 신규 판매 중지 · CONFIRMED 유지

stale → 노출 0 · 신규 reject `INVENTORY_STALE`. 기존 CONFIRMED 체크인 readiness는 정상 평가.

### 3.7 Ready 0 → 배정 실패

배정 fail → 키 차단. Admin “배정 실패” 배지. 보상 UI 없음.

### 3.8 연박 판매

체크인 C, 체크아웃 C+n (n≥2).  
**수락 조건:** 호텔형 `∀ night ∈ [C, C+n) : available_to_sell(night) ≥ 1` (요청 시점에 전 night 동시 검사). 한 night라도 부족하면 reject `AT_CAPACITY` (부족 night 목록을 응답 meta에).  
특정방: 전 night 구간 겹침 없으면 accept.

### 3.9 결제 stub 단일 경로

예약 직후 `payment_status=UNPAID`. Guest “결제 완료” 1버튼만. 실패/부분결제 UI 없음. Admin도 동일 버튼 가능(감사 로그 없이 과제 단순화 — 본인확인 override와 구분).

### 3.10 체크인 시간창

| 조건 | checkin_eligible |
|------|------------------|
| `today < checkin_date` | false — “체크인 당일부터 가능” |
| `today == checkin_date` AND `local_time >= 15:00` (가정) | true |
| `today == checkin_date` AND `local_time < 15:00` | false — “15:00부터 체크인” (얼리CI는 TODO-PARK/부가와 별도, **이번 스콥 아웃**) |
| `today > checkin_date` AND not checked_in | true (늦은 체크인 허용, 과제) |
| `today >= checkout_date` | false — “체크아웃일이 지났습니다” |

`checkin_eligible=false`이면 배정·키 CTA 모두 비활성. readiness 체크리스트는 보이되 키는 막음.

---

## 4. 정책 및 비즈니스 로직

### 4.1 판매 모드 · 재고 (ADR-001)

#### reason_code ← 수식 실패 분기 (문구는 아래 표와 1:1)

호텔형 `accept_one_night` / `accept_stay` 실패 시 **첫 해당 분기**의 코드만 반환:

| 우선 | 조건 | reason_code |
|------|------|-------------|
| 1 | `list_price == null` | `PRICE_MISSING` |
| 2 | `NOT inventory_sync_fresh` | `INVENTORY_STALE` |
| 3 | `available_to_sell(d)==0` AND `sold(d) >= max_sellable(d)` AND `NOT gate_ok` AND `days_until < min_lead_days` AND `sold >= capacity` | `OVERBOOK_GATE_LEAD` |
| 4 | `available_to_sell==0` AND `NOT gate_ok` AND `capacity < min_capacity_for_overbook` AND `sold >= capacity` | `OVERBOOK_GATE_SIZE` |
| 5 | `available_to_sell==0` AND `sold >= max_sellable` (그 외: rate=0·capacity마감·게이트OK이나 이미 +α 소진) | `AT_CAPACITY` |
| — | 특정방 겹침 | `UNIT_UNAVAILABLE` |
| — | FOR UPDATE 패자 | `CONFLICT` |

`days_until=0`은 별도 규칙이 아니라 **리드 게이트 실패 → 위 3 또는 5**로만 처리 (표에 “당일 특별 금지” 문구 금지).

#### reason_code → 문구 (채널 = Admin = Guest 공유)

| reason_code | 표시 텍스트 |
|-------------|------------|
| `AT_CAPACITY` | “해당 날짜 객실이 마감되었습니다” |
| `OVERBOOK_GATE_LEAD` | “임박 일정은 추가 판매할 수 없습니다” |
| `OVERBOOK_GATE_SIZE` | “이 타입은 오버부킹을 허용하지 않습니다” |
| `UNIT_UNAVAILABLE` | “선택한 객실은 이미 예약되었습니다” |
| `INVENTORY_STALE` | “재고 동기화 지연 — 잠시 후 다시 시도” |
| `PRICE_MISSING` | “요금이 설정되지 않았습니다” |
| `MODE_LOCKED` | “예약이 있어 판매 모드를 바꿀 수 없습니다” |
| `READINESS_NOT_MET` | “입실 준비가 끝나지 않았습니다” (+ 첫 No-Go flag 문구) |
| `CHECKIN_WINDOW` | checkin_eligible 문구 (§3.10) |
| `CONFLICT` | “다른 예약이 먼저 확정되었습니다” |

#### 계산식 (Admin `available` 컬럼 ≡ accept 판정 — 동일 식)

**호텔형 — 단일 night d, 룸타입 t** (`checkin_date` 후보가 d인 예약 기준 `days_until`):

```
capacity      = room_type.capacity
confirmed(d)  = COUNT CONFIRMED whose stay covers night d on t
held(d)       = 0   # MVP
sold(d)       = confirmed(d) + held(d)
days_until(d) = d - today_in(property.timezone)   # 정수 일, 당일 0

gate_ok(d) =
  mode == HOTEL_POOL
  AND days_until(d) >= min_lead_days
  AND capacity >= min_capacity_for_overbook
  AND overbook_rate > 0

max_sellable(d) =
  if gate_ok(d) then floor(capacity * (1 + overbook_rate)) else capacity

available_to_sell(d) = max(0, max_sellable(d) - sold(d))

inventory_sync_fresh = (now - channel.last_sync_at) <= stale_ttl_sec

accept_one_night(d) iff
  available_to_sell(d) >= 1
  AND inventory_sync_fresh
  AND list_price != null
```

**호텔형 — 연박 [C, C+n):**

```
accept_stay iff ∀ night ∈ [C, C+n): accept_one_night(night)
# 단일 트랜잭션 + FOR UPDATE. 실패 시 전체 롤백.
# reject 시 short_nights = nights where NOT accept_one_night
```

**특정방 — 유닛 u, [start,end):**

```
accept iff
  no CONFIRMED/HELD on u overlapping [start,end)
  AND list_price != null AND inventory_sync_fresh
```

**레이스:** FOR UPDATE 하에 available=1이면 1건만 accept, 동시 패자 `CONFLICT`.

**기본값:** `min_lead_days=3`, `min_capacity_for_overbook=10`, `overbook_rate=0`, `stale_ttl=300s`.

**임박+기존 오버북분:** 신규만 차단. 기존 CONFIRMED 유지.

**배정 (호텔형, checkin_eligible 필수):**

```
candidates = Ready units of type, not occupied overlapping stay
empty → fail; else unit_code ASC first
```

### 4.2 체크인 readiness

| flag | Go 조건 | No-Go 문구 |
|------|---------|------------|
| `identity_verified` | 본인확인 완료 | “본인확인이 필요합니다” |
| `payment_ok` | `PAID` | “결제를 완료해 주세요” |
| `inventory_ok` | 특정방: 유효 유닛 / 호텔형: 배정됨 또는 Ready≥1 | “배정 가능한 객실이 없습니다” |
| `unit_assigned` | `room_unit_id != null` | “객실 배정 전입니다” |
| `room_ready` | 배정 유닛 Ready | “객실 준비 중 (청소)” |
| `key_issuable` | stub can_issue (기본 true) | “키 발급 시스템에 문제가 있습니다” |

```
all_go = AND(flags)
issue_key iff checkin_eligible AND all_go
```

**No-Go 평가·노출 우선순위 (첫 사유 = 이 순서의 첫 No-Go):**  
1 `checkin_eligible` (창 밖이면 flag보다 우선, reason `CHECKIN_WINDOW`)  
2 `payment_ok` → 3 `identity_verified` → 4 `inventory_ok` → 5 `unit_assigned` → 6 `room_ready` → 7 `key_issuable`

**키 발급 후 Dirty:** `key_issued_at != null`이면 키 UI 유지(회수 없음). Admin 배지 “발급 후 Dirty — 현장 확인”. 미발급이면 즉시 키 CTA disable.

**본인확인 override:** Admin만. `actor_id`+`reason`+`at`. force-key 금지.

#### Guest · 예약 상세 — 필드→UI

| 필드 | 표시 | CTA |
|------|------|-----|
| stay / room_type or unit | 헤더 | — |
| payment_status | UNPAID/PAID | UNPAID면 “결제 완료” |
| identity_verified | Go/No-Go 행 | No-Go면 “본인확인” |
| readiness flags[] | 체크리스트 6행 | — |
| first_blocker | 상단 배지 문구 | — |
| checkin_eligible | 창 안내 | false면 배정·키 숨김/비활성 |
| key | 코드/QR 또는 비활성 버튼 | all_go∧eligible 시 “키 받기” |

#### Admin · HK / 예약 목록

| 요소 | 동작 |
|------|------|
| 유닛 행 hk_status 셀렉트 | Dirty/Cleaning/Ready 즉시 저장 → readiness 재계산 |
| 예약 목록 필터 | CONFIRMED / CANCELLED / 전체 |
| 예약 행 “취소” | CANCELLED, sold↓, reason 없음(과제) |
| 예약 행 “본인확인 override” | 모달 reason 필수 |

### 4.3 HK

Dirty/Cleaning → No-Go. Ready → Go·배정 후보.  
체크아웃 시뮬 → Dirty.

**배정 직후 Dirty:** `room_ready`·`inventory_ok` 재평가 → No-Go. 키 회수 UI 없음(미발급이면 차단, 이미 발급했으면 과제에서 “키 유지+운영 배지”만 — 실회수 스콥 아웃).

### 4.4 CMS stub

| 동작 | 규칙 |
|------|------|
| pull | 중앙 available. 캐시 수량 금지 |
| push | 중앙 결정만. reject 시 채널 UI 롤백 |
| stale | last_sync_at 기준. CONFIRMED 유지 |

### 4.5 Admin 재고 UI (클릭 동작)

| 요소 | 동작 |
|------|------|
| ◀ ▶ 날짜 | 포커스 날짜 ±1일 (지점 TZ 자정). “7일” 칩 → 주간 그리드 |
| 룸타입 셀렉트 | 그리드 필터 |
| 셀 클릭 | 사이드: sold 리스트, gate_ok, max/available/sold 숫자 |
| “예약 생성(stub)” | channel=direct 예약 폼 |
| mode 변경 | 잠금 시 disabled + MODE_LOCKED |
| “동기화 시뮬” | last_sync_at=now |

#### Admin · 룸타입 설정 — 필드→UI

| 필드 | 표시 | 검증/CTA |
|------|------|----------|
| mode | 셀렉트 UNIT\|HOTEL_POOL | 예약 있으면 disabled |
| capacity | int | ≥0. 0이면 배지 |
| min_lead_days | int | ≥0 |
| min_capacity_for_overbook | int | ≥1 |
| overbook_rate | 0~1 decimal | 0=오버북 꺼짐 힌트 |
| list_price | KRW integer, `₩#,###` | null이면 판매 거부 |
| timezone | 읽기전용(지점) | — |
| 저장 | — | CONFIRMED 불변, 미래 available 재계산 |

#### 채널 stub — 필드→UI

| 필드/요소 | 표시 | CTA |
|-----------|------|-----|
| channel_id | 탭 direct/ota_a/ota_b | 탭 전환 |
| available | 중앙 pull 숫자 | — |
| list_price | `₩#,###` | — |
| last_sync_at | 상대시각 | “pull” → sync 갱신 |
| 예약 요청 | 날짜·인원 폼 | submit → accept/reject+reason |
| reject | reason 문구 | 롤백(폼 유지) |

#### Guest · 호텔형 배정 CTA

| 조건 | 버튼 “체크인·배정” | 결과 |
|------|-------------------|------|
| mode=UNIT | 숨김 | — |
| NOT checkin_eligible | disabled | CHECKIN_WINDOW |
| eligible ∧ unit null | enabled | 성공→unit / 실패→배지 |
| unit already set | “키 받기”만 | 재배정 없음 |

표시 포맷: 날짜 `YYYY-MM-DD (지점 TZ)`, 키 stub = 6자리 코드 + QR 동일 문자열.

---

## 5. 상태 의존성 매트릭스

| 트리거 | 갱신 대상 | 갱신 범위 | 비고 |
|--------|----------|----------|------|
| 게이트/capacity/price 변경 | max_sellable, 채널 노출 | 해당 룸타입·미래 night | CONFIRMED 불변 |
| 예약 accept/CONFLICT | sold, 노출, 로그 | night×타입 | 직렬화 |
| 예약 cancel | sold↓ | 동일 | |
| 결제 stub | payment_ok, 키 CTA | 예약 | |
| 본인확인 / override | identity, audit | 예약 | |
| HK 변경 | room_ready, 후보 풀, readiness | 유닛·배정된 예약 | 배정 후 Dirty 포함 |
| 배정 성공/실패 | unit_assigned, inventory_ok | 예약 | |
| 키 발급 | key_issued_at, checked_in=true | 예약 | eligible∧all_go |
| 예약 취소 | status=CANCELLED, sold↓, unit 해제(호텔형) | 예약·재고 | 키 무효(미발급과 동일 UI) |
| sync stale | 노출 0, 신규 reject | 타입·날짜 | CONFIRMED 유지 |
| mode 변경 시도 | — | — | 예약 있으면 거부 |
| “동기화 시뮬”/pull | last_sync_at | 채널 | stale 해제 |

### 호텔형 배정 전/후 × readiness

| 단계 | unit_assigned | room_ready | inventory_ok (대표) |
|------|---------------|------------|---------------------|
| 예약 직후 | No-Go | No-Go(유닛 없음) | Ready≥1이면 Go 가능 / 0이면 No-Go |
| 배정 성공 + Ready | Go | Go | Go |
| 배정 성공 + Dirty | Go | No-Go | No-Go |
| 배정 실패 | No-Go | No-Go | No-Go |

### 키 CTA 조합

| checkin_eligible | all_go | 키 CTA |
|------------------|--------|--------|
| false | * | disabled + CHECKIN_WINDOW 문구 |
| true | true | enabled |
| true | false | disabled + 첫 No-Go |

| mode | 배정 UI |
|------|---------|
| UNIT | 숨김 |
| HOTEL_POOL | checkin_eligible 시 “체크인·배정” |

---

## 6. 엣지 케이스 및 예외 처리

### 경계값

| 상황 | 처리 | 지면 |
|------|------|------|
| available=0 | CTA 비활성 + §4.1 수식 분기 reason | 채널·Admin |
| capacity=0 | 판매 불가 배지 | Admin |
| overbook_rate=0 | max=capacity, “오버북 꺼짐” | Admin |
| held 미구현 | 0 | 전역 |
| 연박 중 1night 실패 | 전체 reject + short_nights[] + 해당 reason | 채널 |
| 동시 마지막 1재고 2요청 | 1 accept, 1 CONFLICT | API |
| CONFIRMED + stale | 신규만 막음. 체크인 계속 | CMS·Guest |
| 배정 직후 Dirty (미발급) | 키 차단 | Guest·Admin |
| 키 발급 후 Dirty | 키 유지 + Admin 배지 | Guest·Admin |
| checked_in=true | 재배정 숨김, 키 재조회 OK | Guest |
| 연박×stale | INVENTORY_STALE | 채널 |
| 연박×레이스 | CONFLICT, 부분 커밋 없음 | API |
| 배정실패×미인증 | No-Go 우선순위 | Guest |
| TZ 자정 경계 | 지점 TZ | 전 지면 |
| 키 네트워크 실패 | idempotent 재시도 | Guest |
| 잘못된 예약 id | 404 | 전 지면 |

### Nullable

| 항목 | 없을 때 |
|------|---------|
| room_unit (호텔형 직후) | 정상, unit_assigned No-Go |
| room_unit (특정방 create) | create 실패 |
| hk_status | 기본 Dirty |
| list_price | PRICE_MISSING |
| sync timestamp | stale |

---

## 7. 이벤트 설계

실험 단위 = **§8 TC 묶음 1회 전부 실행**. 통과율 = pass_count / total_TC.

| 이벤트 | 트리거 | 속성 (필수 unless ?) | 타입 |
|--------|--------|----------------------|------|
| `inventory_quote` | 재고 조회 | mode, room_type_id\|unit_id, date, max_sellable, available, gate_ok | enum, id, date, int, int, bool |
| `booking_decision` | 예약 결과 | decision, reason_code?, reservation_id?, short_nights? | accept\|reject, reason enum, id?, date[]? |
| `readiness_evaluated` | 상세·키 전 | flags, all_go, checkin_eligible, first_blocker? | map, bool, bool, flag\|window? |
| `key_issue_decision` | 키 요청 | decision, reason_code? | allow\|deny, reason enum? |
| `unit_assigned` | 배정 | reservation_id, ok, unit_id? | id, bool, id? |
| `identity_override` | Admin | actor_id, reservation_id, reason | id, id, string |

`reason_code` enum = §4.1 표.

---

## 8. 데모·테스트 케이스 (완료 정의)

| ID | 기대 |
|----|------|
| TC-OB-01 | 게이트 실패 reject |
| TC-OB-02 | 특정방 겹침 reject |
| TC-OB-03 | rate>0·게이트 OK 시 capacity+α까지, 이후 reject |
| TC-OB-04 | 연박 중 1night 마감 → 전체 reject |
| TC-OB-05 | 동시 2요청 → 1 CONFLICT |
| TC-CI-BLOCK-01 | Dirty → 키 deny |
| TC-CI-BLOCK-02 | 미인증 → 키 deny |
| TC-CI-BLOCK-03 | checkin_eligible false → 키 deny |
| TC-CI-BLOCK-04 | 배정 후 Dirty → 키 deny |
| TC-CI-OK-01 | All Go + eligible → 키 allow |
| TC-CMS-01 | stale → 신규 reject, CONFIRMED 체크인 가능 |
| TC-ADMIN-01 | 예약 있는 룸타입 mode 변경 → MODE_LOCKED |

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|------|------|----------|
| 2026-09-25 | v1 | 초안 |
| 2026-09-25 | v1.1 | eval 보강: 설정단위·모드잠금·연박·시간창·결제경로·레이스·실험분기·배정×HK 매트릭스 |
| 2026-09-25 | v1.2 | FS/EC: 수식 전개·FOR UPDATE·TZ·No-Go 우선순위·Guest/HK UI표·조합 엣지·이벤트 스키마 |
| 2026-09-25 | v1.3 | reason←수식 분기, Admin/채널/배정 UI 계약, sync 주체, checked_in, 경계표 정합 |
