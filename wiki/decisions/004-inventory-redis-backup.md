# ADR-004: 재고 Redis 역할 · 백업·복구

- **상태:** Proposed
- **날짜:** 2026-09-25
- **맥락:** 멀티채널·결제 held(TTL) 경로에서 DB만으로 동시성·만료를 다루면 경합·stuck hold가 커진다. Redis를 붙이되 유실·부분실패가 **오버북·입실 사고**로 이어지지 않게 역할·TX 경계·모드별 키·백업·복구를 고정한다.

## 결정

**Postgres(`inventory_*`)가 재고 SSOT다. Redis는 held 가속·경합용 hot layer다.**  
비즈니스 연속성 **1차 백업 = Postgres**. Redis AOF/RDB는 **프로세스 재시작 생존**용이며, wipe 시 Postgres에서 재건한다.

ADR-003을 **수정하지 않는다.** Redis 호출은 **어떤 `@Transactional` 경계 안에도 넣지 않는다**.  
**허가(판매 OK)의 최종 근거는 항상 Postgres TX다.** Redis FULL/NX 실패는 빠른 거절일 뿐, Redis OK만으로 커밋하지 않는다.

### 0) 스코프 · ADR-001 연계

| 항목 | 결정 |
|------|------|
| held TTL | **15분** (결제 정책서·PRD). 다이렉트 `PENDING_PAYMENT`. Admin화는 후속 |
| CMS PRD `held=0` | CMS·채널 **확정 동기화 MVP**. **다이렉트 held는 본 ADR·결제 PRD 우선** |
| ADR-001 미결 | **TTL 수치·저장소**는 본 ADR이 닫음. confirmed 전환 원자성=ADR-003 |

### 1) 모드별 키 · held 규칙 (ADR-001 1:1)

#### 특정방 (`SellMode.UNIT`)

| 항목 | 규칙 |
|------|------|
| 의미 | 유닛×**날짜(박)** 이진 점유. 구간 겹침 = 이중판매 → **박 단위로 막는다** |
| Redis | 숙박의 각 night `d ∈ [checkIn, checkOut)` 에 `SET NX` `inv:hold:unit:{propertyId}:{unitId}:{d}` = `holdId`, **EX = TTL초**. 일부만 성공하면 성공분 DEL 후 거절 |
| Postgres | `inventory_unit_night(property_id, unit_id, stay_date)` **PK/UNIQUE** — hold·confirmed 모두 이 행으로 점유. *가정:* hold 시 INSERT, release/expire 시 DELETE, confirm 시 행 유지+hold 상태만 전이(또는 `state` 컬럼). **daterange EXCLUDE도 허용 대안**이나 MVP는 **박 단위 UNIQUE** |
| 겹침 예시 | 기존 1/1–1/3(박 1/1,1/2) HELD/CONFIRMED 있을 때 1/2–1/4 요청 → 박 1/2 에서 NX·PK 충돌 → **거절** |
| confirm | hold → CONFIRMED; Redis 해당 박 키 DEL. 유닛 배정은 booking (ADR-002) |

#### 호텔형 (`SellMode.HOTEL_POOL`)

| 항목 | 규칙 |
|------|------|
| 의미 | 룸타입×박 카운트. **모든 모드에서** `held(d)+confirmed(d) ≤ max_sellable(d)` 를 **PG TX가 최종 검사** |
| Redis | `inv:held:pool:{propertyId}:{roomTypeId}:{stayDate}` 정수 카운터 — Lua INCR+cap (가속). **카운터에 TTL 없음** (아래 §1b) |
| hold 메타 | (선택) `inv:hold:meta:{holdId}` = JSON/slot, **EX = TTL초** — 만료 신호·디버그용. 카운터와 수명을 묶지 않음 |
| Postgres | `inventory_hold` + `inventory_sold.sold`(confirmed). TX 안: 박마다 `sold` 행 `FOR UPDATE` 후 `count(HELD overlapping night)+sold+1 ≤ max_sellable` 검사 → 통과 시 hold INSERT |
| confirm | 박마다 `sold += 1`, hold → CONFIRMED; after-commit Redis DECR |

##### §1b POOL 카운터 TTL 정책 (**채택: TTL 없음**)

| 옵션 | 결과 |
|------|------|
| **T1 (채택)** | held 카운터 **TTL 없음**. 수명은 `inventory_hold.expires_at` + Expire/reconcile 배치(≤1분)가 DECR | **채택** |
| T2 | INCR마다 EXPIRE 갱신 | 기각 — 만료 시각이 hold마다 달라 키가 “가장 늦은 TTL”로 늘어나 stuck/조기삭제 모두 애매 |
| T3 | `v==1`일 때만 EXPIRE | 기각 — 선발 hold TTL에 키 삭제 → undercount·이중판매 창 |

```lua
-- hold_pool_night(key, cap) → "OK"|"FULL"   -- TTL 인자 없음
local v = redis.call('INCR', KEYS[1])
if v > tonumber(ARGV[1]) then
  redis.call('DECR', KEYS[1])
  return 'FULL'
end
return 'OK'
```

**예시** (max_sellable 22, confirmed 20 → cap 2)

- INCR 1·2 → OK; 3 → FULL  
- UNIT: 겹치는 박 SET NX 실패 → 거절  

**quote:** Redis miss ≠ available. 항상 PG(또는 gate) 재계산 가능해야 함.

### 2) TX · Redis 오케스트레이션 (ADR-003 정합)

```
# --- Non-TX: Redis 사전 점유 (NORMAL만; 실패=빠른 거절) ---
UNIT: 박마다 SET NX … EX ttl
POOL: 박마다 Lua INCR(cap)     # cap은 직전 PG 스냅샷 기준 근사치 — 최종 아님
부분 실패 → Redis 보상 해제 후 REJECTED
Redis DOWN → §5 DEGRADE (이 단계 스킵)

# --- TX-Prepare (DB만, NORMAL·DEGRADE 공통) ---
# 박 목록은 오름차순 정렬 후 잠금 (데드락 방지)
nights = sorted([checkIn .. checkOut))
max_sellable(d) = OverbookGate(ADR-001)  # TX가 쓰는 값. Redis cap은 그 근사

UNIT: for d in nights: INSERT unit_night(p,u,d)  # PK 충돌=거절
      INSERT inventory_hold

POOL: for d in nights:
        sold_row = SELECT … FOR UPDATE inventory_sold (p, roomType, d)
        held_cnt = COUNT(*) FROM inventory_hold
          WHERE status='HELD'
            AND property_id=:p AND room_type_or_unit_id=:rt
            AND check_in <= :d AND check_out > :d   # covering night
        if held_cnt + sold_row.sold + 1 > max_sellable(d) → rollback 거절
      INSERT inventory_hold
# 커밋 실패 시 after-rollback: Redis 보상 DEL/DECR

# --- confirmHold (TX, DB만) — hold와 대칭 ---
hold = SELECT … FOR UPDATE inventory_hold
assert status=HELD
nights = sorted(…)
POOL: for d in nights: sold FOR UPDATE; sold += 1
UNIT: unit_night 유지 (state→CONFIRMED 또는 hold status만 CONFIRMED)
hold.status = CONFIRMED
# after-commit: UNIT 박 키 DEL / POOL DECR; 실패=P6 → reconcile

# --- releaseHold / TX-Expire (TX, DB만) ---
hold FOR UPDATE; if already RELEASED return
POOL: (sold 불변 — held만 해제)
UNIT: DELETE unit_night for nights of this hold (CONFIRMED가 아닐 때)
hold → RELEASED/DELETE
# after-commit: Redis DEL/DECR; 실패 → reconcile
```

**Redis는 가속이다. NORMAL에서도 POOL/UNIT PG 검사가 빠지면 구현 위반이다.**  
인덱스 가정: `(property_id, room_type_or_unit_id, status, check_in, check_out)` covering 조회용.

#### 부분실패 표

| # | Redis 사전 | Postgres TX(상한·UNIQUE) | after Redis | 결과 | 회복 |
|---|------------|--------------------------|-------------|------|------|
| P1 | OK | OK | OK | 정상 | — |
| P2 | OK | 롤백 | 보상 DEL/DECR | 거절 | 보상 실패→TTL/배치 |
| P3 | OK | OK | 정리 실패 | DB 진실 | reconcile |
| P4 | NX/FULL | 안 함 | — | 거절 | — |
| P5 | DOWN | DEGRADE: DB만 상한 | — | §5 | — |
| P6 | OK | confirm OK | DECR 실패 | confirmed=DB | reconcile DECR |
| P7 | OK(undercount) | **PG 상한 거절** | 보상 | 거절 | AOF/드리프트 방어 |

본 ADR은 ADR-003 **보완**(non-Supersede).

### 3) Redis 영속성 · 백업

| 항목 | 채택 |
|------|------|
| 인스턴스 | 단일. **logical DB `1`** = inventory only → `FLUSHDB`로 초기화 |
| AOF | `appendonly yes`, `appendfsync everysec` (~1s 창; 최종 방어=PG) |
| RDB | `save 60 1000` |
| S3 RDB 일배 | 필수 아님 |
| 1차 백업 | **Postgres**. *가정 RPO ≤ 5분* (일 스냅샷+WAL/PITR) → 인프라 ADR에서 확정 |

```
# redis.conf (docker 커밋)
appendonly yes
appendfsync everysec
save 60 1000
```

### 4) 재해 복구 · reconcile · redis_mode

**`inventory_redis_mode`** (Postgres, 단일 행): `mode` ∈ {NORMAL, DEGRADE, REBUILDING}, `updated_at`, `leader_instance_id`.  
모든 `app-api`/`app-batch` 인스턴스가 **이 행을 읽어** Redis 호출 여부를 결정한다 (프로세스 로컬 메모리만 믿지 않음).

```
rebuild_redis_from_postgres(instance_id):
  -- CAS: rowcount=0 이면 FLUSHDB 금지·즉시 return
  UPDATE inventory_redis_mode
     SET mode='REBUILDING', leader_instance_id=:instance_id, updated_at=now()
   WHERE mode <> 'REBUILDING'
     AND (leader_instance_id IS NULL OR leader_instance_id = :instance_id)
  -- RETURNING / rowcount 확인

  SELECT redis DB 1; FLUSHDB
  for hold in HELD AND expires_at > now():
    UNIT: 박마다 SET key holdId EX seconds_until(expires_at)
    POOL: 박마다 INCR (cap 없이 재건; 다음 hold의 PG 상한이 방어)
  reconcile 1회 성공 후
    UPDATE … SET mode='NORMAL', leader_instance_id=NULL WHERE leader_instance_id=:instance_id

reconcile_loop (≤1분):
  PG 기준 Redis 키/카운터 보정
  expires_at≤now AND HELD → releaseHold (SLA: 만료 후 ≤1분)
```

### 5) 모드 전이

| 모드 | 진입 | 동작 |
|------|------|------|
| NORMAL | mode 행=NORMAL ∧ ping OK | Redis 사전점유 **+** §2 PG 상한(의무) |
| DEGRADE | ping 연속 실패 *가정 3회* / 수동 / mode 행 | Redis **호출 금지**. §2 PG만 |
| REBUILDING | rebuild CAS 성공 | DEGRADE와 동일. FLUSHDB는 리더만 |
| 탈출 | ping OK ∧ rebuild 끝 ∧ reconcile 성공 → mode=NORMAL | |

**금지:** miss→available 확대. half-open(인스턴스마다 다른 mode). REBUILDING 중 NX/INCR.

### 6) 모듈 경계

- Redis·`inventory_redis_mode`: `module-inventory`만.
- booking → `InventoryApi`만 (ADR-002).
- `@Transactional` 내 Redis 호출 = 위반 (ArchUnit).
- 가정: Lettuce, docker Redis+conf.

## 대안과 기각

| 옵션 | 요약 | 정합 | 운영 | 결과 |
|------|------|------|------|------|
| **A (채택)** | PG SSOT+상한 + Redis 가속 + TX 밖 사전점유 + AOF everysec + 카운터 TTL 없음 | ADR-001/003 | 재건·reconcile | **채택** |
| B | Redis SSOT | 유실=오버북 | RDB 필수 | 기각 |
| C | Redis 없음 | 단순 | 경합·TTL 신호 약함 | 기각 — hot path 포기 |
| D | Cluster+S3 필수 | 과잉 | 초기 부담 | 기각 |
| E | TX 안 Redis | ADR-003 위반 | orphan | 기각 |
| F | UNIT exact checkIn/checkOut 키만 | 구간 겹침 허용 | — | 기각 — ADR-001 위반 |

## 왜 이렇게 하나

- 실패 비용이 **입실·보상**이다. Redis 허가·exact 구간 키·카운터 TTL은 각각 이중판매 창을 연다.
- held는 ephemeral(15분)이지만 **카운터 수명을 Redis EXPIRE에 맡기면** PG HELD와 어긋난다 → 배치는 PG `expires_at`이 주인.
- TX 안 Redis는 커밋 의미를 거짓으로 만든다 (ADR-003과 동일 논거).

## 아직 안 정한 것

- [ ] keyspace notification — 초기 **불필요**(배치≤1분으로 Accepted 가능)
- [ ] quote 캐시 실도입
- [ ] Postgres PITR 수치 확정 (가정 RPO≤5분)
- [ ] DEGRADE N회·Lettuce timeout ms
- [ ] orphan 큐 UI vs 로그
- [ ] UNIT: 박 UNIQUE vs daterange EXCLUDE — **MVP=박 UNIQUE**, EXCLUDE는 성능 이슈 시

## 결과 (구현에 미치는 영향)

- `inventory_unit_night` (또는 동등 UNIQUE) DDL + POOL hold TX의 `FOR UPDATE` 상한.
- Redis 어댑터·Lua(T1)·`inventory_redis_mode` 행·리더 CAS rebuild.
- docker Redis + conf; app DB index 1.
- batch: Expire·reconcile ≤1분.
- Accepted 시 ADR-001 held TTL 체크리스트 → 15분·본 ADR 링크.
- README: Redis=hot, 백업 1차=Postgres.

## 참고

- [ADR-001](./001-inventory-overbooking.md) · [ADR-002](./002-module-boundaries.md) · [ADR-003](./003-payment-idempotency-tx.md) (보완)
- [결제 held 15분](../prd/payment-settlement/policy.md) · [CMS held=0 MVP](../prd/cms-checkin-core/prd.md)
- Redis AOF everysec · Lua INCR+cap · after-commit 보상
