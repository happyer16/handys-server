# 과제 제출 노트

- **갱신:** 2026-09-25
- **브랜치 기준:** `main`
- **한 줄 스코프:** 이번 작은 결과물은 **결제·정산 멱등(ADR-003)** 이고, 판매모드·오버북·체크인 readiness는 **규칙·일부 데모**까지다.

평가자가 보는 것([product-context](./product-context.md)): 구현 + **문제 정의 / 범위 / AI 검토**.  
상세 근거는 ADR·PRD, 실행은 [README](../README.md) · [docs/demo/](../docs/demo/).

---

## 1. 스코프 (제출 축 vs 방향만)

| 축 | 상태 | 근거 |
|----|------|------|
| 다이렉트 Intent 멱등 · PG TX 밖 · `held→confirmed` | **구현 + IT** | ADR-003 · payment PRD |
| OTA payout Posted · 오너 월정산 · 불일치 큐 | **구현 + 단위/IT** | payment PRD |
| 특정방/호텔형 · 오버북 게이트 | **도메인 + quote/book stub** | ADR-001 · CMS PRD |
| 체크인 readiness · 키 stub | **얇은 데모** (`R-1001` Dirty → 키 차단) | CMS PRD · `MvpFlowTest` |
| 실 PG · 실 OTA · 프라이싱 · 주차 · CS MCP · Outbox/Kafka | **스콥 아웃** | README “나중에” |

**의도적으로 얇은 모듈:** `channel`(sync/quote/book stub), `checkin`(readiness·키), `property`(마스터+HK). 결제 코어는 `booking/payment`에 모여 있다.

---

## 2. 데모 시드 ID

`app-api` 기동 시 [`CmsDemoDataLoader`](../app-api/src/main/kotlin/co/handys/config/CmsDemoDataLoader.kt)가 한 번 시드한다. (`P-SEOUL-01` 있으면 스킵)

| ID | 의미 |
|----|------|
| `P-SEOUL-01` | 데모 지점 (Asia/Seoul, held TTL 관련 property 설정 300분 표기) |
| `RT-DELUXE` | 호텔형 풀 · capacity 10 · `minLeadDays=3` · `overbookRate=0` |
| `U-301` | Dirty (+ 오늘 체크인 예약 `R-1001` 배정) — 키 No-Go용 |
| `U-302` | Ready |
| `R-1001` | 오늘 체크인 CONFIRMED · PAID · 본인확인됨 · 유닛 Dirty → `room_ready` 차단 |
| 채널 | `direct` · `ota_a` · `ota_b` (sync fresh) |

**결제 Guest API** (`/guest/reservations`)는 CMS 시드와 별도 예약 모델이다. 데모에서는 `propertyId`/`roomTypeOrUnitId`를 임의 문자열로 써도 hold가 생성된다. (IT와 동일)

---

## 3. TC 커버 표 (payment PRD §엣지)

| TC | 시나리오 | 커버 | 위치 |
|----|----------|------|------|
| TC-PAY-IDEM-01 | charge 5연타 → 청구 1 | ✅ IT | `PaymentIdempotencyIT` |
| TC-PAY-IDEM-02 | 성공 응답 유실 후 재요청 | ✅ 단위 | `ChargePaymentServiceTest` (AlreadySucceeded) |
| TC-PAY-IDEM-03 | 웹훅 3중복 | ✅ 단위 | `HandlePgWebhookServiceTest` |
| TC-PAY-STATE-01 | PG 거절 → CONFIRMED 0 | ✅ IT | `PaymentIdempotencyIT` |
| TC-PAY-MISMATCH-01 | TTL/EXPIRED 후 성공 웹훅 → 큐 | ✅ IT | `PaymentIdempotencyIT` |
| TC-PAY-OTA-01 | OTA 예약 CHARGE Intent 0 | ✅ 단위 | `OwnerSettlementServiceTest` / CreateOta |
| TC-REF-01 | 체크인 25h+ 전 취소 → 전액 환불 | ✅ 단위 | `CancelReservationServiceTest` |
| TC-REF-02 | 임박 취소 → 환불 0 | ✅ 단위 | `CancelReservationServiceTest` |
| TC-STL-DUP-01 | 동일 reservation 이중 집계 방지 | ✅ 단위 | `OwnerSettlementServiceTest` |
| TC-STL-IDEM-01 | 동일 stl 키 2회 | ✅ IT | `PaymentIdempotencyIT` |
| TC-STL-OTA-01 | 체크아웃月 ≠ 입금月 | ✅ 단위 | `OwnerSettlementServiceTest` |

**CMS·체크인 (데모/IT)**

| 시나리오 | 커버 | 위치 |
|----------|------|------|
| 당일 sold-out + lead 게이트 → quote available 0 | ✅ | `MvpFlowTest` |
| Dirty 유닛 → 키 `READINESS_NOT_MET` / `room_ready` | ✅ | `MvpFlowTest` |
| 오버북 게이트 단위 | ✅ | `OverbookGateTest` |

취소·OTA 생성은 **HTTP 미노출**(서비스+테스트만). 수동 데모는 charge·웹훅·정산·CMS readiness 중심 → [`docs/demo/`](../docs/demo/).

---

## 4. AI 활용 · 검토 흔적

| 산출물 | Evaluator | 결과 |
|--------|-----------|------|
| [cms-checkin-core/eval.md](./prd/cms-checkin-core/eval.md) | 별도 에이전트 | 평균 ~3.8, 루프 반영 |
| [payment-settlement/eval.md](./prd/payment-settlement/eval.md) | 별도 에이전트 | 평균 3.5 → PRD 보강 후 active |
| [eval-004.md](./decisions/eval-004.md) | 별도 에이전트 | ADR-004 Redis |
| ADR-001 · 002 · 003 | 문서 Accepted | **정식 eval 파일 없음** — 세션에서 결정 직행. 규칙은 본문에 대안·기각 표로 남김 |

하네스: [`skills/prd-harness`](../skills/prd-harness/SKILL.md) · [`skills/adr-harness`](../skills/adr-harness/SKILL.md)  
원칙: 같은 턴 자기채점 금지 (README AI 섹션).

---

## 5. 회고 (짧게)

**잘 된 판단**
- 문제 컷을 “체크인 UI”가 아니라 **돈·재고 일치(멱등)** 로 잡음 — 무인 Activation 전제.
- 특정방/호텔형·다이렉트/OTA를 섞지 않음.
- PG를 TX 밖으로 뺀 뒤 Mock으로 TC를 고정.

**아쉬운 판단**
- ADR-001~003은 evaluator 루프를 문서 파일로 안 남김 (004·PRD만 eval 파일).
- `checkin`/`channel`은 stub — “모듈 맵만 예쁜” 인상 위험 → 본 노트·README에 명시.
- 취소 HTTP·실 PG·Outbox는 의도적 스콥 아웃이라, 데모는 charge/정산 경로에 집중해야 함.

---

## 6. 제출 직전 체크

- [ ] `./gradlew test` 통과
- [ ] `./gradlew :app-api:bootRun` → Swagger `/swagger-ui.html`
- [ ] [`docs/demo/payment-settlement.http`](../docs/demo/payment-settlement.http) 시나리오 A·B·C
- [ ] [`docs/demo/cms-checkin.http`](../docs/demo/cms-checkin.http) 키 차단 + (선택) Ready 전환 후 발급
- [ ] 시크릿·`.env` 커밋 없음 · `.superpowers/sdd` 로컬 전용(gitignore)
