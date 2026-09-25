---
name: handys-session-wrapup
description: >-
  핸디즈 과제 세션 마무리 파이프라인. 완료 요약 → wiki 지식 저장 →
  (선택) 이슈 싱크 → 세션 아카이브 → 다음 세션 인계. Use when the user says
  session-wrapup, 세션 마무리, handys-session-wrapup, or asks to wrap up
  the Handys assignment session.
---

# Handys 세션 마무리 파이프라인

핸디즈 과제/저장소용 세션 마무리. ADR·wiki·아카이브를 남긴다.

실행 원칙:
- 한 스텝씩 실행 → 결과 공유 → 확인 → 다음 스텝
- 저장/생성은 반드시 승인 후 실행
- 10분 이내로 끝낼 것 — 완벽보다 일관성

---

## Step 0: 세션 기준 정보 확인

워크스페이스가 `handys` 저장소인지 확인한다. (루트에 `wiki/` 가 있으면 OK.)

선택 설정 파일: `.cursor/handys-config.json` (없으면 아래 기본값 사용)

```json
{
  "name": "{이름}",
  "sessions_root": "sessions",
  "wiki_root": "wiki",
  "jira": {
    "enabled": false,
    "project_key": null
  }
}
```

기본값:
- `sessions_root`: 저장소 루트 기준 `sessions/`
- `wiki_root`: `wiki/`
- Jira: **비활성** (과제 저장소 기본). config에서 `enabled: true`일 때만 Step 0.5 실행

대화에서 자동 추출:
- **날짜**: 오늘 (YYYYMMDD / YYYY-MM-DD)
- **관련 영역**: 제품 컨텍스트 / ADR / 구현 / 과제 제출 등
- **주요 작업**: 생성·수정된 파일 목록

불분명하면 질문하지 말고 대화 내용으로 추론한다.

---

## Step 0.5: Jira 티켓 연결 (선택)

`handys-config.json`의 `jira.enabled`가 `true`일 때만 실행한다.  
아니면 이 스텝을 **Pass**하고 `"Jira 연결 없음 — 아카이브만 진행"`이라고 짧게 알린다.

활성일 때 판단 순서:
1. 대화에서 티켓 ID/URL이 보이면 자동 사용
2. 있으면 유저에게 ID 확인, 없으면 제목 확인 후 생성(사용 가능한 Jira MCP/도구로)
3. 이후 스텝에서 `{JIRA_TICKET_ID}`, `{JIRA_TICKET_URL}`, `{JIRA_TICKET_TITLE}` 사용

비활성이면 아카이브 frontmatter의 `jira` 필드는 생략한다.

---

## Step 1: 세션 요약 작성

다음 형식으로 요약 초안 작성:

```
### 완료한 것
- 

### 결정한 것
- 

### 다음 세션에 넘길 것
- 

### 잘 된 것 / 아쉬운 것
- 잘 됨: 
- 아쉬움: 
```

→ 초안 제시 → 수정 없으면 그대로 진행

---

## Step 2: 지식으로 저장할 것 있는지 확인

핸디즈 wiki 기준으로 체크:
- 새로운 기술/제품 결정·방향 전환? → `wiki/decisions/NNN-slug.md` + `wiki/README.md` 인덱스 갱신
- 제품·도메인 컨텍스트 보강? → `wiki/` 하위 (예: `wiki/product-context.md`)
- 과제 제출/평가 관점 메모? → `wiki/assignment-notes.md` (없으면 생성 제안)
- 에이전트/스킬 개선 인사이트? → 아카이브의 "다음 개선" 섹션에만 남기고 Pass 가능

저장할 게 있으면 **파일 경로 제안 → 승인 후 저장**.  
없으면 `"이번 세션은 wiki 업데이트 없음"` 확인 후 Pass.

이미 이번 세션에서 ADR/wiki를 쓴 경우, 중복 저장하지 말고 "이미 반영됨"으로 체크만 한다.

---

## Step 3: 이슈 / 다음 액션 싱크

액션 아이템·미완료 작업 확인:
- 다음 세션에 넘길 것 → 아카이브 "다음 세션"에 명확히 남김
- Jira enabled면 → 이슈 등록/상태 업데이트 **제안 후 승인 시만** 실행
- Jira 없으면 → 이슈 생성 없이 아카이브 체크리스트로 대체

없으면 Pass.

---

## Step 4: 세션 아카이브 저장

저장 경로:

```
{sessions_root}/YYMM/YYMMDD-{slug}.md
```

예: `sessions/2509/250925-inventory-overbooking-adr.md`

`sessions_root`가 상대경로면 저장소 루트 기준.

```markdown
---
date: YYYY-MM-DD
type: session-archive
project: handys
jira: {JIRA_TICKET_ID}   # enabled일 때만
tags: [session, handys]
---

# 세션 아카이브 — YYYY-MM-DD

## 주요 작업
{Step 1 요약 붙여넣기}

## 수정된 파일
- 

## Wiki / ADR
- 신규·갱신: 
- 스킵: 

## 다음 세션 인계
- [ ] 

## 회고 메모
### 잘 된 판단
- 

### 아쉬운 판단 / 갭
- 

### 반복 패턴 의심
- 
```

→ 초안 제시 → **승인 후** 저장

---

## Step 5: 회고 재료 플래그

Step 4 "회고 메모"에 내용이 있으면:

```
⚑ 회고 재료 있음
  → 다음 wrapup / 주간 정리 때 참고
  → 파일: {archive path}
```

없으면 생략.

---

## Step 6: 대화 이름 제안

사이드바에서 찾기 쉽게:

```
제안 이름: "{핵심 작업 2-3단어} — {MMDD}"
예시: "재고 오버부킹 ADR — 0925"
```

→ Cursor에서 이름 변경이 가능하면 `rename_chat` 등으로 제안/적용.  
불가하면 사용자가 UI에서 변경하도록 안내.

---

**산출물**: Step 1 요약 + Step 4 아카이브 + (있으면) wiki/ADR 갱신 + 대화 이름 제안
