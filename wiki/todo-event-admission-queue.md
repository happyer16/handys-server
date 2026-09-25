# TODO — 행사·대형 예약 (대기열 · 만료)

- **상태:** 스콥 아웃 (제출 후 별도 구현)
- **날짜:** 2026-09-25
- **전제:** 재고 SSOT·결제 멱등은 그대로 ([ADR-001](./decisions/001-inventory-overbooking.md) · [ADR-003](./decisions/003-payment-idempotency-tx.md) · [ADR-004](./decisions/004-inventory-redis-backup.md)). 본 문서는 **유입 폭주 앞단**만 다룬다.

## 문제

오픈런·프로모·대형 단체처럼 **짧은 창에 요청이 몰리면** 예약 API·DB·PG가 같이 터진다.  
오버북 게이트·Intent 멱등만으로는 “줄 서기”가 없어, 전원에게 동시에 Prepare를 열어 **held 고갈·타임아웃 폭주**가 난다.

## 기술 스택 (방향)

| 층 | 역할 | 스택 |
|----|------|------|
| **1. 진입 게이트** | 줄 세우기 · 초당 입장 수 제한 | **Redis Sorted Set** (+ 선택 Lua) |
| **2. 입장 티켓** | 게이트 통과 증명 (짧은 lease) | Redis String/`SET` + TTL 또는 JWT claim |
| **3. 예약 Prepare** | hold + Intent (기존) | Postgres TX + Redis held 가속 (ADR-004) |
| **4. 결제** | PG Non-TX + Finalize (기존) | Mock→실 PG, ADR-003 |
| **5. 만료·회수** | 줄·티켓·held 정리 | Redis TTL + `app-batch` expire |

```
Client → [ZSET 대기열] → admit N/sec → [입장 티켓 TTL]
              ↓ 티켓 있을 때만
         POST /guest/reservations (Prepare)
              ↓
         hold + Intent expires_at (예: 15분)
              ↓
         charge → confirmed 또는 expire → 재고 반환
```

게이트는 **판매 허가의 SSOT가 아니다.** ZSET OK만으로 방을 팔지 않고, 최종은 기존처럼 Postgres + ADR-001 게이트.

## 1) Redis Sorted Set — 진입점

**키 예:** `evt:queue:{eventId}`  
**member:** `userId` 또는 `sessionId` (중복 입장 방지)  
**score:** 대기 시작 epoch ms (FIFO) — 추첨식이면 random score + 별도 시드

| 단계 | 명령 (개념) | 의미 |
|------|-------------|------|
| 줄 서기 | `ZADD NX` | 이미 줄이면 순위만 조회 |
| 순위 | `ZRANK` / `ZCARD` | UX: “내 앞 N명” |
| 입장 | Lua: `ZRANGE 0 admit-1` → 티켓 발급 → `ZREM` | **초당/분당 admit 상한**으로 API 보호 |
| 줄 만료 | `ZREMRANGEBYSCORE -inf (now - queue_ttl_ms)` | 오래 안 온 대기자 제거 |

**가정 (초기):** `admit_rate` · `queue_ttl`(예: 30~60분) · `max_queue` 는 행사별 어드민 설정.  
동시 다행사면 `eventId`로 키 분리. 특정방/호텔형 재고 규칙과 무관하게 **트래픽만** 자른다.

## 2) 진입 후에도 — 예약·결제 만료

입장 = 예약 확정이 아니다. **짧은 창만** 연다.

| 단계 | TTL / 만료 | 관리 |
|------|------------|------|
| **입장 티켓** | 짧음 (예: 1~3분) | Redis `SET evt:ticket:{userId} EX …`. 만료 시 다시 줄 |
| **Prepare held + Intent** | 기존 **15분** (ADR-004 / 결제 PRD) | `expires_at` + `ExpirePaymentIntents` / held reconcile 배치 |
| **줄(ZSET) 잔류** | `queue_ttl` | score 기준 일괄 제거 — 좀비 member 방지 |
| **확정 실패·이탈** | hold release | 티켓·줄 slot 즉시 반환 가능하면 `ZADD` 재진입 또는 버림(정책) |

```
줄 TTL          : 대기만 하고 안 들어오면 컷
티켓 TTL        : 들어왔는데 Prepare 안 하면 컷 → 다음 사람 admit
Intent/held TTL : 결제 안 하면 재고 반환 (지금 코어와 동일)
```

**중요:** 티켓 TTL ≪ held TTL. 게이트에서 많이 들여보내고 held만 쌓이게 두지 않는다.  
`admit_rate`는 **동시 in-flight Prepare 상한**과 맞춰 잡는다 (대략 `admit × ticket_ttl ≲ max_inflight_holds`).

## 3) API · 모듈 위치 (나중에)

| 컴포넌트 | 위치 후보 |
|----------|-----------|
| Queue/Admit 서비스 | `app-api` 앞단 또는 `module-channel` 옆 얇은 `event-admission` 패키지 |
| 예약·결제 | 기존 `booking/payment` — **티켓 헤더/클레임 검증만** 추가 |
| 배치 | `app-batch`: ZSET 청소 + 기존 Intent/held expire (스케줄러는 api에 두지 않음) |

예약 생성 요청에 `X-Event-Ticket` (또는 세션) 없으면 **429/403**. 티켓 검증은 Redis GET — **DB TX 밖** (ADR-003/004와 동일 원칙).

## 4) 하지 않는 것 (이 TODO에서)

- ZSET을 재고 카운터로 쓰기 (재고는 ADR-001/004)
- 대기열 통과 = `CONFIRMED` (결제는 ADR-003)
- 행사 전용 MSA 필수화 — 모듈식 모놀리스에 게이트만 얹어도 됨
- 이번 과제 구현·데모

## 한 줄

**Sorted Set으로 줄 세우고 → 짧은 티켓으로만 Prepare를 열고 → held/Intent TTL로 재고를 회수한다.** 폭주는 게이트에서, 돈·방은 기존 코어에서.
