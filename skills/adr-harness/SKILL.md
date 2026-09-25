---
name: adr-harness
description: Use when writing or reviewing an ADR, 기술 결정, 재고/오버북/배정 규칙, wiki/decisions 문서; or when the user mentions adr-harness, ADR, architecture decision.
---

# ADR Harness

기술·도메인 규칙은 **ADR → (필요 시) 평가 루프**로 남긴다. 제품 성공 정의·실험은 [prd-harness](../prd-harness/SKILL.md)로 `wiki/prd/`에 둔다.

템플릿: [tpl-adr.md](tpl-adr.md)  
평가: [adr-evaluator.md](adr-evaluator.md)

## 모드

| 입력 | 모드 |
|------|------|
| 없음 / 결정 주제 | **생성** (Step 0~5) |
| 기존 ADR 경로 | **평가** (Step E) |

## 산출물 경로

```
wiki/decisions/
  NNN-kebab-title.md    # ADR 본문
  eval-NNN.md           # 최근 평가 (선택)
```

번호 `NNN`은 기존 파일의 최댓값 + 1. 제목은 kebab-case.

---

## 생성 모드

### Step 0 — 컨텍스트

반드시 읽는다.

1. [tpl-adr.md](tpl-adr.md)
2. 기존 `wiki/decisions/**` (충돌·중복 확인)
3. 관련 `wiki/prd/**`가 있으면 성공 정의·스콥과 맞는지 확인

### Step 1 — 입력

| 항목 | 설명 |
|------|------|
| 맥락 | 왜 지금 이 결정이 필요한가 |
| 옵션 | 최소 2개 대안 (채택 / 기각) |
| 결정 | 무엇을 택했는가 |
| 결과 | 구현·다른 ADR·PRD에 미치는 영향 |

짧아도 된다. 가정은 `가정:`으로 표시.

Handys 기본 감각:

- 특정방 vs 호텔형 풀은 재고·오버북·배정 시점이 다르다 (ADR-001)
- 오버북은 **경험 리스크 우선, 보수적**. 어드민 설정으로 풀되 기본은 좁게
- 무인·앱 체크인이면 배정 실패 = 입실 사고로 이어진다

### Step 2 — 초안

[tpl-adr.md](tpl-adr.md) 구조를 따른다. 섹션을 빼지 않는다.

**게이트**

- 상태가 있는가 (`Proposed` / `Accepted` / `Superseded`)
- 대안이 최소 하나 기각 이유와 함께 있는가
- “아직 안 정한 것”이 체크리스트로 남아 있는가
- 구현 영향이 API/도메인/제약 수준으로 구체적인가
- 기존 Accepted ADR과 모순되면: 수정안을 쓰거나 Supersede 관계를 명시

저장: `wiki/decisions/NNN-….md`, 기본 상태 `Proposed` (사용자가 바로 확정 요청하면 `Accepted`)

### Step 3 — Evaluator (별도 에이전트)

자기 채점 금지. Task 서브에이전트:

```
subagent_type: generalPurpose
description: ADR 평가
prompt: {adr-evaluator.md 전문}

평가 대상: {ADR 전문}
관련 ADR: {다른 decisions 요약}
관련 PRD: {있으면}
```

결과를 `wiki/decisions/eval-NNN.md`에 저장한다.

### Step 4 — 결과 제시

```
📋 ADR 평가 결과

| 차원 | 점수 |
|------|------|
| CX 맥락 명확성 | X/5 |
| AL 대안 충분성 | X/5 |
| RC 규칙 구체성 | X/5 |
| CN 일관성 | X/5 |
| IM 구현 영향 | X/5 |
| OQ 열린 질문 | X/5 |
| 평균 | X.X |

1. 자동 보강 → 재평가
2. 특정 섹션 수정
3. 이대로 Accepted
4. Proposed로 유지
```

### Step 5 — 확정

옵션 1/2: 수정 후 Step 3. **최대 3회.**  
옵션 3: `상태: Accepted`, `wiki/README.md` 인덱스에 한 줄 추가.  
옵션 4: Proposed 유지, 인덱스에는 “검토 중”으로 넣어도 됨.

---

## 평가 모드

기존 ADR만 채점. 자동 수정은 요청 후에.

---

## 금지

- 제품 성공 지표·실험을 ADR에만 쓰고 PRD를 건너뛰기 (그건 prd-harness)
- 같은 턴 자기 5점
- 기존 Accepted와 조용히 모순되는 규칙 추가
- 외부 vault / Notion 경로 찾기

## 말투

한국어, 짧고 직접적. 추측은 `가정:`.
