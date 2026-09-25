---
date: 2026-09-25
type: session-archive
project: handys
tags: [session, handys]
---

# 세션 아카이브 — 2026-09-25

## 주요 작업

### 완료한 것
- 핸디즈 제품/사업/채용 맥락 정리 (어반스테이·르컬렉티브, TMS/PMS, 무인 운영)
- 게스트·운영 여정 분해 + 과제 후보 문제 10개
- ADR-001 재고·오버부킹 (특정방 vs 호텔형 + 기간/규모/비율 게이트, 어드민·보수적 기본)
- `handys-session-wrapup` 스킬 이식
- 제품 컨텍스트·문제 후보 wiki 고정

### 결정한 것
- 특정방: 오버북 0, 예약 시 배정 / 호텔형: 풀 카운트, 체크인 시 배정
- 오버북은 기간·규모·비율 게이트 + 어드민 설정; `min_lead_days` 숫자는 미확정(D+3도 위험?); 경험 리스크 우선·보수적 기본

### 다음 세션에 넘길 것
- 문제 후보 1~2개 골라 `성공 정의 → MVP → 안 할 것 → 지표`
- ADR 오픈: `min_lead_days` 기본값, 설정 단위(전역/지점/타입), 임박 시 기존 오버북 처리

### 잘 된 것 / 아쉬운 것
- 잘 됨: 구현 전에 판매 모드·오버북 게이트를 ADR로 못 박음
- 아쉬움: (wrapup 전) 컨텍스트/문제 리스트가 채팅에만 있었음 → wiki로 고정 완료

## 수정된 파일
- `wiki/README.md`
- `wiki/product-context.md` (신규)
- `wiki/problem-candidates.md` (신규)
- `wiki/decisions/001-inventory-overbooking.md`
- `.cursor/skills/handys-session-wrapup/SKILL.md`
- `sessions/2509/250925-context-inventory-adr.md` (본 파일)

## Wiki / ADR
- 신규·갱신: product-context, problem-candidates, ADR-001, wiki 인덱스, session-wrapup 스킬
- 스킵: 없음

## 다음 세션 인계
- [ ] 문제 1~2개 선택 후 성공 정의·MVP·안 할 것·지표
- [ ] (선택) #1 재고면 ADR-001 전제로 PRD/`prd-harness` 착수
- [ ] ADR 오픈 이슈: min_lead_days 기본값(?), 설정 단위, 임박 오버북분 처리

## 회고 메모
### 잘 된 판단
- 오버북을 “허용/금지” 이분법이 아니라 기간·규모·어드민·보수 기본으로 나눔 — 무인 경험 리스크와 맞음

### 아쉬운 판단 / 갭
- 어반스테이가 운영상 특정방인지 호텔형 풀인지 아직 미확인 (ADR 오픈)

### 반복 패턴 의심
- 결정이 채팅에만 남기 쉬움 → wrapup + wiki 고정 루프가 필요해서 스킬을 옮김
