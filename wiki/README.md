# Handys 과제 위키

핸디즈(Plott OS) 과제용 메모. 기술 결정은 `decisions/`, 제품 기획은 `prd/`에 쌓는다. 서버 모듈 경계는 ADR-002.

AI로 쓸 때: [`prd-harness`](../skills/prd-harness/SKILL.md) · [`adr-harness`](../skills/adr-harness/SKILL.md)  
전체 흐름: [루트 README — AI 활용 · 검토](../README.md#ai-활용--검토)

## 인덱스

### Context

| 문서 | 요약 |
|------|------|
| [**과제 제출 노트**](./assignment-notes.md) | 스코프 · 시드 · TC 커버 · AI 흔적 · 회고 · 제출 체크 |
| [일하는 방식](./working-style.md) | wrapup은 매번 X → 모아서 나중에 회고 |
| [제품 컨텍스트](./product-context.md) | 핸디즈 사업부문·PLOTT 브랜드·Plott OS·과제 평가 관점 ([handys.co.kr](https://handys.co.kr/)) |
| [문제 후보 10개](./problem-candidates.md) | 게스트/운영 여정 + 과제용 PON |

### Decisions (ADR)

| 문서 | 요약 |
|------|------|
| [ADR-001 재고·오버부킹 모델](./decisions/001-inventory-overbooking.md) | 특정방 vs 호텔형. 오버북은 기간·규모·비율 게이트 + 어드민 보수 설정 |
| [ADR-002 서버 모듈 경계](./decisions/002-module-boundaries.md) | 모듈식 모놀리스. property→inventory→booking→checkin + channel·app-api |
| [ADR-003 결제 멱등성·트랜잭션 단위](./decisions/003-payment-idempotency-tx.md) | PG 호출은 TX 밖. Prepare/Finalize 분리 + `idempotency_record` 키 고정 |
| [ADR-004 재고 Redis·백업](./decisions/004-inventory-redis-backup.md) | Postgres SSOT + Redis held 가속. AOF everysec. 백업 1차=Postgres (Proposed) |

### PRD

| 문서 | 요약 |
|------|------|
| [CMS·체크인 정책서](./prd/cms-checkin-core/policy.md) | Plott OS 01+04 Do now. 08 프라이싱·05 주차는 TODO · PRD active (eval 3.8) |
| [결제·정산·멱등성 정책서](./prd/payment-settlement/policy.md) | 다이렉트 즉시전액 + OTA 채널정산. Intent 멱등·오너 월 recognition · [구현 계획](../docs/superpowers/plans/2026-09-25-payment-settlement.md) |
