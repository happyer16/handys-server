---
name: prd-harness
description: Use when writing a 정책서, PRD, 기획서, or 스펙; evaluating an existing PRD; or when the user mentions prd-harness, 토스 PO, RARRA, 17질문, PON, 문제 정의, or Handys product planning.
---

# PRD Harness

기획은 **정책서 → PRD → 평가 루프** 순이다. 정책서 없이 PRD를 쓰지 않는다.

근거: 토스 PO Session, RARRA의 중요성, 문제 해결사 17가지 질문.
전문: [toss-po.md](toss-po.md) · [rarra.md](rarra.md) · [seventeen-questions.md](seventeen-questions.md)
템플릿: [tpl-policy.md](tpl-policy.md) · [tpl-prd.md](tpl-prd.md)
평가: [prd-evaluator.md](prd-evaluator.md)

기술 결정(재고 모드, 오버북 게이트 등)은 이 하네스가 아니라 [adr-harness](../adr-harness/SKILL.md)로 `wiki/decisions/`에 남긴다.

## 모드

| 입력 | 모드 |
|------|------|
| 없음 / 목표·가설·제품 형태 | **생성** (Step 0~7) |
| 기존 정책서/PRD 경로 | **평가** (Step E) |
| `evolve` | 이 프로젝트에선 사용하지 않음. 안내 후 중단 |

## 산출물 경로

```
wiki/prd/{slug}/
  policy.md    # 정책서 (PO / RARRA / 17질문)
  prd.md       # PRD
  eval.md      # 최근 평가 결과
```

`{slug}`는 기능명을 kebab-case로. 기존 폴더가 있으면 재사용, 없으면 사용자에게 확인하지 말고 합리적인 slug를 정한 뒤 알린다.

---

## 생성 모드

### Step 0 — 원칙 로딩

아래를 **반드시 Read**한다. 읽지 않고 기억으로 쓰지 않는다.

1. [toss-po.md](toss-po.md)
2. [rarra.md](rarra.md)
3. [seventeen-questions.md](seventeen-questions.md)
4. [tpl-policy.md](tpl-policy.md)
5. [tpl-prd.md](tpl-prd.md)

원본 PDF가 필요하면 `references/`에서 확인한다.

관련 ADR이 있으면 `wiki/decisions/**`도 읽는다. 정책·PRD가 ADR과 모순되면 ADR을 먼저 고치거나, ADR 미결로 남긴다.

---

### Step 1 — 입력 수집

부족하면 한 번에 묶어서 묻는다. 짧아도 된다. 가정은 `가정:`으로 표시하고 진행한다.

**필수**

| 항목 | 설명 |
|------|------|
| 목표 | 이 기능으로 달성하려는 것 |
| 가설 | 검증하려는 핵심 가설 |
| 제품 형태 | 대략적인 지면/형태 |

**선택:** 타겟 유저, 스콥 아웃, 관련 문서/앱 화면, 디자인

Handys(운영·예약·숙박) 과제면 기본 가정:

- 도메인: 예약/재고/체크인·입실 readiness (무인·앱 체크인 가능)
- 허영 지표(설치 수, PV)로 성공을 정의하지 않음
- 판매 모드(특정방 vs 호텔형 풀)와 오버북 게이트는 ADR을 따른다
- 실행 가능한 프로토타입이 필수 → 최소 가치 단위로 쪼갠다

---

### Step 2 — 컨텍스트

이 레포에서 관련 맥락을 모은다.

- 기존 `wiki/prd/**`, `wiki/decisions/**`가 있으면 읽는다
- 앱/웹 화면, 이전 대화에서 정한 문제가 있으면 반영한다
- 외부 vault / Notion / QMD / team-config는 쓰지 않는다

`{context}`에 저장한다.

---

### Step 2.5 — Clarifier

PRD가 아니라 **정책서 전에** 미결을 닫는다. 가정으로 채울 수 있으면 채우고, 방향이 갈리면 사용자에게 묻는다.

| # | 항목 | 확인할 것 |
|---|------|----------|
| 1 | 빌드 단계 | 로드맵/과제에서 어느 단계인가 |
| 2 | RARRA 병목 | Retention / Activation / … 중 어디인가 |
| 3 | 검증 방법 | 정량 / 프로토타입 / 과제 제출물 |
| 4 | 스콥 경계 | 인접 기능·ADR과 겹치는가 |
| 5 | UI 상태 | 초기값, 기본 동작 |
| 6 | 정책 분기 | 기본 경로가 무엇인가 |
| 7 | 데이터 단위 | 지점/룸타입/유닛/예약 등 단위 |
| 8 | 진입 경로 | 이 화면에 어떻게 오는가 |
| 9 | 판매 모드 | 특정방 / 호텔형 / 둘 다 — ADR과 일치하는가 |

결과를 `{clarified_decisions}`에 저장한다.

---

### Step 3 — 정책서 작성 (필수, PRD보다 먼저)

[tpl-policy.md](tpl-policy.md)를 그대로 따른다. 섹션을 빼지 않는다. 모르겠으면 `가정:`으로 채운다.

**게이트 — 하나라도 실패하면 정책서를 확정하지 않는다.**

- 한 줄 성공 정의가 Result인가 (Action이 아닌가)
- RARRA 병목이 Acquisition으로 시작하면 되돌린다
- 17질문 중 이번 건에 해당하는 항목에 답이 있는가
- 실험의 true/false 다음 액션이 있는가
- 안 하기로 한 것이 있는가
- 2주를 넘는 일은 쪼갰는가
- 재고·오버북·배정 규칙은 기존 ADR과 모순되지 않는가 (모순이면 adr-harness로 먼저)

저장: `wiki/prd/{slug}/policy.md`, frontmatter `status: draft`

사용자에게 정책서 요약을 보여주고 **승인받기 전까지 PRD를 쓰지 않는다.**

```
정책서를 작성했습니다: wiki/prd/{slug}/policy.md

다음 중 선택해주세요:
1. 이대로 PRD 작성
2. 정책서 수정
3. 문제 재정의 (PON부터)
```

---

### Step 4 — PRD 초안

정책서가 승인된 뒤에만 진행한다. [tpl-prd.md](tpl-prd.md) 구조를 따른다.

작성 원칙:

- 정책서의 성공 정의 / 가설 / 스콥 아웃을 **그대로** 가져온다. PRD가 정책과 다른 목표를 세우면 안 된다.
- 스콥 인/아웃 테이블 필수. 아웃에는 **왜 제외하는지**
- 지표는 핵심 / 모니터링 / 가드레일. 각 지표에 목표값 + 측정 방법
- 시나리오는 step-by-step. 각 단계에 **시스템이 보여주는 것**
- 데이터→UI 매핑은 테이블. 계산식은 수식
- UI에 셀렉터/토글/필터가 있으면 상태 의존성 필수
- 경계값(0, null, max) 규칙을 모든 지면에 일관 적용
- 재고·오버북·배정은 ADR의 게이트/모드를 인용한다 (PRD에서 새 규칙을 발명하지 않음)

저장: `wiki/prd/{slug}/prd.md`, `status: draft`

---

### Step 5 — Evaluator (별도 에이전트)

같은 컨텍스트에서 자기 평가하지 않는다. Task 서브에이전트를 스폰한다.

```
subagent_type: generalPurpose
description: PRD 평가
prompt: {prd-evaluator.md 전문}

아래를 평가하라.
- 정책서: {policy.md 전문}
- PRD: {prd.md 전문}
- 관련 ADR: {wiki/decisions 해당 문서, 있으면}

정책서와 PRD가 어긋나면 HA를 감점한다.
ADR과 재고/오버북 규칙이 어긋나면 SC·EC를 감점한다.
```

결과를 `{eval_result}`에 넣고 `wiki/prd/{slug}/eval.md`에 저장한다.

---

### Step 6 — 결과 제시

```
📋 PRD 평가 결과

| 차원 | 점수 |
|------|------|
| SC 스콥 명확성 | X/5 |
| FS 플로우 충분성 | X/5 |
| SD 상태 의존성 | X/5 |
| EC 엣지케이스 | X/5 |
| ME 측정 가능성 | X/5 |
| HA 가설 정합성 | X/5 |
| 평균 | X.X |

다음 중 선택해주세요:
1. 개선 필요 항목 자동 보강 → 재평가
2. 특정 섹션 수정 지시
3. PRD 전문 보기
4. 이대로 확정
```

---

### Step 7 — 피드백 루프 / 확정

옵션 1/2: 수정 후 Step 5로. **최대 3회.**
옵션 3: 파일 경로와 핵심만 보여준다. 전문을 채팅에 덤프하지 않는다.
옵션 4: `policy.md`와 `prd.md`의 `status`를 `active`로 바꾼다.

3회 후에도 3점 미만 차원이 있으면 알리고 확정 여부를 묻는다.

```
✅ 기획 확정

📄 정책서: wiki/prd/{slug}/policy.md
📄 PRD: wiki/prd/{slug}/prd.md
📊 평가: 평균 {X.X}/5 (SC FS SD EC ME HA)
```

---

## 평가 모드

기존 `policy.md` / `prd.md` 또는 사용자가 붙인 문서를 Step 5와 같이 채점한다. 자동 보강은 하지 않고 피드백만 준다. 수정을 요청하면 해당 파일을 직접 고친다.

---

## 금지

- 정책서 없이 PRD부터 쓰기
- 리텐션/Activation 검증 없이 Acquisition/마케팅 스케일 제안
- 성공 정의를 가입자 수, PV, 설치 수로 쓰기
- 가설이 틀렸을 때 다음 액션 없이 실험이라고 부르기
- 같은 턴에서 작성한 PRD를 스스로 5점 주기
- 외부 Notion / QMD / team-config / vault 경로를 찾기
- ADR에 없는 재고·오버북 규칙을 PRD에서 새로 만들기 (필요하면 adr-harness 먼저)

## 말투

- 한국어, 짧고 직접적으로
- 추측은 `가정:`
- 해결책보다 가치·병목·실험을 먼저
- 사용자가 스펙만 달라고 해도 한 줄 성공 정의는 붙인다
