# Handys

Plott OS 과제 — CMS·재고·체크인 코어. 결정·기획은 `wiki/`, AI 하네스는 `skills/`, 서버는 Kotlin/Spring Boot 모듈식 모놀리스.

## 서버 모듈 맵

근거: [ADR-002](./wiki/decisions/002-module-boundaries.md)

```
app-api                  # Spring Boot REST 진입점
app-batch                # 배치 전용 진입점 (스케줄러만 — 정산 등)
modules/
  common                 # 공유 커널 (도메인 로직 금지)
  property               # 지점·룸타입·유닛·판매모드
  inventory              # 가용·오버북 게이트 (ADR-001)
  booking                # 예약 · 결제 · 오너 정산 유스케이스
  checkin                # readiness · 호텔형 입실 배정
  channel                # OTA/다이렉트 어댑터 (얇게)
```

의존 방향 (역방향 금지):

`property ← inventory ← booking ← checkin` · `channel → inventory` · `* → common` · `app-api` / `app-batch` → 도메인 모듈

```bash
./gradlew :app-api:bootRun      # http://localhost:8080/health
./gradlew :app-batch:bootRun    # http://localhost:8081/actuator/health — 정산 배치만
./gradlew :app-api:bootJar
```

정산 배치는 **app-batch에서만** 스케줄되며 (`UNIQUE(job_date)`로 동일 날짜 중복 실행 방지). app-api에는 스케줄러가 없다.

## AI 활용 · 검토

기획·기술 결정을 AI로 초안 잡고, **별도 Evaluator 에이전트**로 채점한 뒤 피드백 루프로 고친다. 같은 턴에서 스스로 만점 주지 않는다.

| 스킬 | 역할 | 산출물 |
|------|------|--------|
| [`skills/prd-harness`](./skills/prd-harness/SKILL.md) | 정책서 → PRD → 6차원 평가 | `wiki/prd/{slug}/` |
| [`skills/adr-harness`](./skills/adr-harness/SKILL.md) | ADR 작성 → 6차원 평가 | `wiki/decisions/` |
| [`skills/handys-session-wrapup`](./skills/handys-session-wrapup/SKILL.md) | 세션 마무리·wiki 인계 | `sessions/` · wiki 갱신 |

Cursor는 `.cursor/skills/` 심볼릭 링크로 위 스킬을 로드한다.

**호출 예**

- `prd-harness` / `정책서 쓰자` / `PRD 평가해줘`
- `adr-harness` / `ADR 남겨` / `001 평가해줘`
- `session-wrapup` / `세션 마무리`

근거 자료(토스 PO · RARRA · 17질문): [`skills/prd-harness/references/`](./skills/prd-harness/references/)

## 위키

인덱스: [`wiki/README.md`](./wiki/README.md)

| 경로 | 내용 |
|------|------|
| `wiki/decisions/` | ADR (재고·오버부킹, 모듈 경계) |
| `wiki/prd/` | 정책서·PRD·평가 |

## 레포 구조

| 경로 | 역할 |
|------|------|
| `app-api/` · `app-batch/` · `modules/` | Spring Boot 멀티 모듈 (ADR-002 + 배치 진입점) |
| `wiki/` | 사람이 읽는 산출물 |
| `skills/` | AI 스킬 (과제에서 활용·검토 과정이 보이게) |
| `.cursor/skills/` | Cursor 로드용 링크 |
