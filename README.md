# Handys

Plott OS 과제 — 운영·예약·숙박 도메인의 작은 결과물.  
결정·기획은 `wiki/`, AI 하네스는 `skills/`, 서버는 Kotlin/Spring Boot 모듈식 모놀리스.

**제출 한 줄:** 이번 작은 결과물은 **결제·정산 멱등(이중청구·이중정산 0)** 이다. 판매모드·오버북·체크인 readiness는 규칙·얇은 데모까지.

| 축 | 상태 |
|----|------|
| 다이렉트 Intent 멱등 · PG TX 밖 · OTA 정산 · 오너 월정산 | **구현** — [ADR-003](./wiki/decisions/003-payment-idempotency-tx.md) · [정책서](./wiki/prd/payment-settlement/policy.md) |
| 특정방/호텔형 · 오버북 게이트 · readiness/키 stub | **규칙 + 얇은 데모** — [ADR-001](./wiki/decisions/001-inventory-overbooking.md) · [CMS·체크인](./wiki/prd/cms-checkin-core/policy.md) |

제출 체크·TC 커버·회고: [`wiki/assignment-notes.md`](./wiki/assignment-notes.md)

---

## 문제 정의

무인·앱 운영에서 사고가 나는 지점은 “문의 폭주”가 아니라 **팔 수 있는 방**과 **돈·채널이 맞는 예약**이 끊기는 순간이다. 그중에서도 아래 세 축을 먼저 고정했다.

### 1) 판매하는 방 형태 — 특정방 vs 호텔형

같은 “방 1개”라도 약속의 의미가 다르다.

| 모드 | 게스트가 산 것 | 재고 | 오버북 | 배정 |
|------|----------------|------|--------|------|
| **특정방** (자체 매물·Airbnb-like) | **그 호수** | 유닛·날짜 가능/불가 | **금지** | 예약 시 확정 |
| **호텔형** (룸타입 풀) | **타입 1박** | 날짜별 카운트 | **조건부만** | 체크인 시 호수 |

한 규칙으로 뭉개면 특정방은 약속 위반이 되고, 호텔형은 취소·노쇼를 못 흡수해 점유만 깎인다. → [ADR-001](./wiki/decisions/001-inventory-overbooking.md)

### 2) 자체 매물(다이렉트) vs OTA — 돈의 흐름을 섞지 않음

| 채널 | 누가 수금 | 예약 확정 | 오너 정산 |
|------|-----------|-----------|-----------|
| **다이렉트 (자체)** | 우리 PG | Intent 성공 시에만 `held→confirmed` | 체크아웃 월 인식 |
| **OTA** | 채널 | 채널 확정 + 재고 규칙 | 채널 입금(Posted) 월에만 인식 |

다이렉트 재시도와 OTA 채널수금을 같은 “결제”로 취급하면 이중청구·이중정산이 난다. 이번 작은 결과물은 **이 경계를 Intent·정산 recognition으로 고정**한 것이다. → [결제·정산 정책](./wiki/prd/payment-settlement/policy.md)

### 3) 오버부킹 컨트롤 — 무인에선 보수적으로

호텔형만 오버북을 열되, **기간 · 규모 · 비율** 게이트를 모두 통과할 때만 허용한다. 기본값은 좁게(잘 안 넘게), 수치는 어드민. 무인·앱 체크인에서는 프론트가 “다른 방”으로 안내하기 어렵기 때문이다.

```
호텔형 + D-day 여유 + 풀 규모 충분 + rate > 0  →  max = floor(capacity × (1 + rate))
그 외 / 특정방                                   →  capacity까지만 (특정방은 항상 0 or 1)
```

---

## 작은 결과물 (이번 제출)

**가설:** 결제 Intent가 멱등하고, 성공 시에만 재고가 확정되며, OTA는 채널 정산으로 분리되면 → 이중청구·미결제 확정·오너 이중정산이 사라진다.

| 포함 | 제외 (의도적) |
|------|----------------|
| Mock PG · Intent 멱등 · PG는 TX 밖 | 실 PG · 실 OTA 연동 |
| 성공 시에만 `held→confirmed` | 스마트 프라이싱 · 주차 |
| OTA payout Posted → 월 정산 (`app-batch` + admin API) | 체크인 UI 풀구현 |
| 불일치 큐 · 취소 24h 이진 환불(서비스·테스트) | 전채널 환불 통일 · 오너스 풀스택 · 취소 HTTP |

### 5분 데모

```bash
./gradlew :app-api:bootRun      # http://localhost:8080 — Swagger `/swagger-ui.html`
./gradlew :app-batch:bootRun    # http://localhost:8081 — 스케줄러만 (api에 없음)
./gradlew test                  # 제출 직전
```

| 파일 | 내용 |
|------|------|
| [`docs/demo/payment-settlement.http`](./docs/demo/payment-settlement.http) | charge 연타 · mismatch · 정산 멱등 |
| [`docs/demo/cms-checkin.http`](./docs/demo/cms-checkin.http) | seed `R-1001` Dirty → 키 차단 → Ready 후 발급 |
| [`docs/demo/charge-sequence.md`](./docs/demo/charge-sequence.md) | Prepare → PG(Non-TX) → Finalize 시퀀스 |

**시드** (`CmsDemoDataLoader`, 기동 1회): `P-SEOUL-01` · `RT-DELUXE` · `U-301`(Dirty)/`U-302`(Ready) · `R-1001` — 상세는 [assignment-notes §2](./wiki/assignment-notes.md#2-데모-시드-id).

정산·재고 expire 배치는 **app-batch에서만** 돈다. 재고는 Postgres SSOT + Redis 가속([ADR-004](./wiki/decisions/004-inventory-redis-backup.md)); 기본은 Redis off.

---

## 나중에 — CS 대응을 MCP로

도메인 규칙(판매 모드 · 채널 · 오버북 게이트 · Intent 상태)을 API/모듈로 고정해 두면, 이후 CS·운영 대응은 **MCP 툴로 같은 진실에 붙여** 유연하게 확장할 수 있다.

| 지금 | 이후 (스콥 아웃, 방향만) |
|------|--------------------------|
| 규칙·상태머신이 코드/ADR에 있음 | Cursor 등에서 MCP로 `예약 조회 · 불일치 큐 · 정산 라인 · 오버북 게이트 상태` 조회 |
| CS는 로그·어드민 화면 의존 | “이 Intent 왜 Pending?” / “OTA Posted 됐나?”를 에이전트가 툴로 답함 |
| 환불·예외는 정책으로만 정의 | 정책 가드레일 안에서 MCP 액션(조회·티켓·제한된 재실행)으로 대응 속도↑ |

**포인트:** MCP는 규칙을 새로 만들지 않는다. **이미 고정한 단일 진실(재고 모드 · 채널 · 결제 Intent)** 위에 CS 워크플로만 얹는다. 코어가 흔들리면 MCP도 흔들리므로, 이번 과제는 코어를 먼저 자른 것이다.

---

## 서버 모듈 맵

근거: [ADR-002](./wiki/decisions/002-module-boundaries.md)

```
app-api                  # Spring Boot REST 진입점
app-batch                # 배치 전용 진입점 (스케줄러만 — 정산 등)
modules/
  common                 # 공유 커널 (도메인 로직 금지)
  property               # 지점·룸타입·유닛·판매모드 (+ HK stub)
  inventory              # 가용·오버북 게이트 · hold (ADR-001/004)
  booking                # 예약 · 결제·정산 코어 (이번 제출 중심)
  checkin                # readiness · 키 stub (얇음 — 데모용)
  channel                # OTA/다이렉트 quote·book stub (얇음)
```

의존 방향 (역방향 금지):

`property ← inventory ← booking ← checkin` · `channel → inventory` · `* → common` · `app-api` / `app-batch` → 도메인 모듈

`checkin`·`channel`은 **스캐폴드+stub**이다. 구조만 예쁜 상태가 되지 않도록 결제 코어와 데모 경로를 제출 축으로 둔다.

---

## AI 활용 · 검토

기획·기술 결정을 AI로 초안 잡고, **별도 Evaluator 에이전트**로 채점한 뒤 피드백 루프로 고친다. 같은 턴에서 스스로 만점 주지 않는다.

| 스킬 | 역할 | 산출물 |
|------|------|--------|
| [`skills/prd-harness`](./skills/prd-harness/SKILL.md) | 정책서 → PRD → 6차원 평가 | `wiki/prd/{slug}/` + `eval.md` |
| [`skills/adr-harness`](./skills/adr-harness/SKILL.md) | ADR 작성 → 6차원 평가 | `wiki/decisions/` (+ `eval-004`) |
| [`skills/handys-session-wrapup`](./skills/handys-session-wrapup/SKILL.md) | 세션 마무리·wiki 인계 | `sessions/` · wiki 갱신 |

평가 파일: [cms eval](./wiki/prd/cms-checkin-core/eval.md) · [payment eval](./wiki/prd/payment-settlement/eval.md) · [ADR-004 eval](./wiki/decisions/eval-004.md).  
ADR-001~003은 Accepted 직행(eval 파일 없음) — [assignment-notes §4](./wiki/assignment-notes.md#4-ai-활용--검토-흔적).

Cursor는 `.cursor/skills/` 심볼릭 링크로 위 스킬을 로드한다.

---

## 고민한 부분

| 주제 | 선택 | 버린 것 / 이유 |
|------|------|----------------|
| **문제 컷** | 판매모드·채널·오버북을 정의한 뒤, **결제·정산 멱등**을 작은 결과물로 | 체크인 UI·프라이싱부터 — Activation 전제(돈·재고 일치)가 없으면 무인 운영이 성립 안 함 |
| **재고 모델** | 특정방 / 호텔형 분리 + 오버북은 기간·규모·비율 게이트 ([ADR-001](./wiki/decisions/001-inventory-overbooking.md)) | 한 규칙으로 뭉개기 |
| **채널 돈** | 다이렉트 즉시전액 + OTA 채널정산 분리 | 한 리포트에 섞기 — 인식 시점이 흔들림 |
| **오버북 기본값** | 보수(좁게). 수치는 어드민 | 매출용 높은 기본율 — 무인에서 대체 안내 불가 |
| **모듈 경계** | 바운디드 컨텍스트 모듈식 모놀리스 ([ADR-002](./wiki/decisions/002-module-boundaries.md)) | 초기 MSA — 소유권·불변식 흐림 |
| **결제·이중청구** | Intent 멱등 · PG는 TX 밖 · 성공 시에만 confirm ([ADR-003](./wiki/decisions/003-payment-idempotency-tx.md)) | TX 안 PG · 클라 UUID 멱등키 |
| **CS 확장** | 코어 API/상태 고정 → 이후 **MCP로 CS 조회·대응** | CS 전용 예외 로직을 코어에 심기 |
| **AI 작업** | 초안 → **별도 Evaluator** 채점 → 수정 | 같은 턴 자기채점 |

---

## 나중에 할 부분

**제품 (스콥 아웃 / TODO)** — [CMS·체크인 정책 §10](./wiki/prd/cms-checkin-core/policy.md#10-후순위-todo-명시)

- TODO-PRICE 스마트 프라이싱 · TODO-PARK 주차·부가결제
- 실 PG · 실 OTA · 채널 환불 전면 통일 · MLOps·오너스 풀스택
- **CS MCP 툴킷** (예약·Intent·불일치 큐·정산 라인 조회 / 가드레일 안 액션)
- **행사·대형 예약 대기열** — Redis ZSET 진입 + 티켓/held TTL ([wiki](./wiki/todo-event-admission-queue.md))

**도메인 미결**

- `min_lead_days` 권장 기본값 · 설정 단위 · 임박 시 기존 오버북분 — [ADR-001](./wiki/decisions/001-inventory-overbooking.md)
- 실방 부족 시 업셀/보상 · 어반스테이 실모드 확인
- 모듈 간 facade vs 이벤트 — [ADR-002](./wiki/decisions/002-module-boundaries.md)
- 실 PG · 만료 후 늦은 성공 자동환불 · in-flight 스테일 락 — [ADR-003](./wiki/decisions/003-payment-idempotency-tx.md)

**구현 다음 스텝**

- inventory 오버북 게이트 본구현 · booking/checkin 최소 유스케이스
- CMS 체크인 readiness + 키 stub 데모
- CS용 MCP 서버(읽기 우선) 초안

**비동기 · 통합 (TODO)**

PG·알림·채널 같은 **외부 호출은 DB TX와 한 원자 단위가 될 수 없다**. 지금 과제는 Mock PG를 TX 밖에서 호출하는 수준([ADR-003](./wiki/decisions/003-payment-idempotency-tx.md)). 실연동·운영 안정화 때는 **Transactional Outbox**로 올린다. (apartsearcher-server 토스 결제와 같은 결: Intent/트랜잭션 기록을 TX에 남기고, 외부 호출·후속 발행은 outbox 릴레이가 담당.)

| TODO | 방향 |
|------|------|
| **결제 외부 호출 → Outbox** | charge/confirm/cancel·웹훅 후속 등 PG 호출은 TX 커밋 후 outbox 폴러/릴레이가 수행. at-least-once + Intent/멱등키로 중복 청구 방지. 동기 Feign/Rest를 `@Transactional` 안에 두지 않음 |
| **알림** | 예약 확정·결제 성공·입실 안내 등 **알림 발송도 outbox → (Kafka) → 알림 워커**로만. 이번 스콥 구현 없음 — TODO |
| **Kafka (또는 동급)** | outbox → 토픽 → `channel` / `checkin` / 정산 consume. 모듈 간 직접 호출 대신 이벤트 연결 ([ADR-002](./wiki/decisions/002-module-boundaries.md)) |
| **당장 하지 않는 것** | 예약 생성 전체를 사가로 쪼개기 — 재고·Intent 불변식은 동기 TX, **외부 호출·알림·채널 부수효과만** outbox |

**행사·대형 예약 (TODO, 별도 구현)** — 상세: [`wiki/todo-event-admission-queue.md`](./wiki/todo-event-admission-queue.md)

오픈런·프로모처럼 요청이 몰리면 오버북/멱등만으로는 held·API가 같이 터진다. **재고·결제는 기존 ADR 유지**, 앞단에만 대기열을 둔다.

| 층 | 기술 | 처리 |
|----|------|------|
| 진입 | **Redis Sorted Set** `evt:queue:{eventId}` | `ZADD` 줄서기 → 초당 `admit`만 입장 → `ZREM` |
| 입장 증명 | Redis ticket `SET` + **짧은 TTL** (1~3분) | 티켓 없으면 Prepare 거부 |
| 예약·결제 | 기존 Prepare / Intent **15분** + ADR-003 | 티켓 OK 후에만 hold |
| 회수 | ZSET score 청소 + held/Intent expire 배치 | 줄·티켓·미결제 재고 반환 |

한 줄: **ZSET으로 줄 → 짧은 티켓으로만 Prepare → held TTL로 방 회수.** ZSET OK ≠ 판매 확정.

---

## 위키 · 레포 구조

인덱스: [`wiki/README.md`](./wiki/README.md) · 제출 노트: [`wiki/assignment-notes.md`](./wiki/assignment-notes.md)

| 경로 | 역할 |
|------|------|
| `wiki/decisions/` | ADR (재고·오버부킹, 모듈 경계, 결제 멱등, Redis) |
| `wiki/prd/` | 정책서·PRD·평가 |
| `docs/demo/` | 제출용 HTTP 데모 · charge 시퀀스 |
| `app-api/` · `app-batch/` · `modules/` | Spring Boot 멀티 모듈 |
| `skills/` · `.cursor/skills/` | AI 하네스 |
| `sessions/` | 세션 아카이브(회고 재료) |
