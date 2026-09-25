---
date: 2026-09-25
type: session-archive
project: handys
tags: [session, handys, modules, spring]
---

# 세션 아카이브 — 2026-09-25

## 주요 작업

### 완료한 것
- 모듈 경계를 실서비스 기준으로 확정 (과제용 운영/예약/숙박 분기 폐기)
- ADR-002 Accepted + wiki 인덱스 반영
- Kotlin/Spring Boot Gradle 멀티 모듈 스캐폴딩 (`app-api` + 6 modules), `bootJar` 통과
- README에 모듈 맵·의존 규칙·실행 커맨드 추가

### 결정한 것
- **모듈식 모놀리스**: 단일 배포 `app-api`, MSA는 보류
- 의존: `property ← inventory ← booking ← checkin`, `channel → inventory`, `* → common`
- “운영”은 도메인 모듈이 아니라 API 역할 레이어
- 프라이싱·주차·정산·HK 풀셋 모듈은 YAGNI로 안 만듦

### 다음 세션에 넘길 것
- ADR-001 오픈: `min_lead_days` 기본값, 설정 단위, 임박 오버북분 처리
- inventory 도메인(오버북 게이트) / booking·checkin 유스케이스 구현 착수
- (선택) 모듈 간 호출 패턴: 동기 facade vs 도메인 이벤트

### 잘 된 것 / 아쉬운 것
- 잘 됨: 과제 문구 대신 트랜잭션·소유권으로 경계를 잡고 ADR로 고정
- 아쉬움: 스캐폴딩만 — 실제 재고/예약 로직·패키지 `domain/application/infra` 뼈대는 아직 thin

## 수정된 파일
- `wiki/decisions/002-module-boundaries.md` (신규)
- `wiki/README.md`
- `README.md`
- `settings.gradle.kts` · `build.gradle.kts` · `gradle.properties` · `gradle/` · `gradlew*`
- `app-api/` · `modules/{common,property,inventory,booking,checkin,channel}/`
- `.gitignore`

## Wiki / ADR
- 신규·갱신: ADR-002 모듈 경계 (Accepted), wiki 인덱스, README 모듈 맵
- 스킵: 제품 컨텍스트·PRD (이번 세션 변경 없음) — **이미 반영됨**, 중복 저장 없음

## 다음 세션 인계
- [ ] ADR-001 오픈 이슈 닫기 (`min_lead_days`, 설정 단위, 임박 오버북분)
- [ ] `module-inventory`에 ADR-001 오버북 게이트 도메인 구현
- [ ] booking / checkin 최소 유스케이스 (특정방 즉시 배정 · 호텔형 입실 배정)
- [ ] (선택) 모듈 간 facade vs 도메인 이벤트 ADR

## 회고 메모
### 잘 된 판단
- 과제 제출자 임의 분기(운영/예약/숙박)를 버리고 불변식·소유권으로 나눔
- 모듈식 모놀리스로 시작해 MSA 비용을 미룸

### 아쉬운 판단 / 갭
- 스캐폴딩 마커/빈 API만 있어, 다음 세션에 도메인 로직이 없으면 “구조만 예쁜” 상태가 됨
- ADR-002 evaluator 루프는 돌리지 않음 (Accepted 직행)

### 반복 패턴 의심
- 구조 결정 → 즉시 ADR/wiki 고정은 잘 작동. 구현 thin 상태를 다음 인계에 명시하는 습관 유지
