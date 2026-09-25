# ADR-002: 서버 모듈 경계 (모듈식 모놀리스)

- **상태:** Accepted
- **날짜:** 2026-09-25
- **맥락:** Spring Boot 멀티 모듈을 “운영/예약/숙박”이 아니라 실서비스 트랜잭션·소유권 경계로 나눈다.

## 결정

배포 단위는 **단일 Spring Boot 앱**(`app-api`). 코드는 Gradle 모듈로 바운디드 컨텍스트를 분리한다. MSA는 팀·트래픽 경계가 생길 때까지 하지 않는다.

### 모듈과 책임

| 모듈 | 책임 | 핵심 규칙 |
|------|------|-----------|
| `module-common` | 에러 타입, 시계/ID, 공유 enum 최소 | 도메인 로직 금지 |
| `module-property` | 지점·룸타입·유닛·판매모드(특정방/호텔형) | 마스터만. 재고 숫자 없음 |
| `module-inventory` | 날짜별 가용, held/confirmed, 오버북 게이트 | ADR-001 단일 진실 |
| `module-booking` | 예약 생성·확정·취소, 특정방 즉시 배정 | 재고는 inventory에만 요청 |
| `module-checkin` | readiness(키·본인·Ready), 호텔형 입실 시 배정 | 판매와 분리된 Go/No-Go |
| `module-channel` | OTA/다이렉트 인입 어댑터(얇게) | inventory(·booking 포트)만 호출 |
| `app-api` | REST·설정·보안·부트스트랩 | 오케스트레이션만 |

### 의존 방향 (한 방향만)

```
property ← inventory ← booking ← checkin
channel  → inventory  (필요 시 booking 생성 포트)
app-api  → 모든 도메인 모듈
*        → common
```

- **역방향 금지:** inventory가 booking/checkin을 알지 않는다.
- 모듈 간 호출은 **공개 API(facade/port)** 만. JPA 엔티티 직접 공유 최소화 (ID + DTO).
- “운영”은 도메인 모듈이 아니라 **역할**(어드민/현장 API 패키지)로 둔다.

### 모듈 내부 패키지

```
…/{context}/
  domain/           # 순수 규칙
  application/      # use case
  infrastructure/   # JPA·외부
  api/              # 다른 모듈용 facade
```

### DB

초기 **단일 DB**. 테이블 prefix로 컨텍스트 구분 (`property_*`, `inventory_*` …). 물리 DB 분리는 경계 안정 후.

### 이번 스콥에 안 만드는 모듈

프라이싱·주차·오너 정산·HK 풀셋 모듈은 YAGNI로 두지 않는다. HK Ready는 checkin이 최소 필드로 시작한다.

## 대안과 기각

| 옵션 | 요약 | 결과 |
|------|------|------|
| A (채택) | 바운디드 컨텍스트 모듈 + 단일 앱 | 채택 |
| B | 운영/예약/숙박 3분 | 기각 — 재고·체크인 소속 모호, 과제용 임의 분기 |
| C | api/domain/infra 레이어만 | 기각 — ADR-001 규칙이 한 덩어리로 뭉개짐 |
| D | 처음부터 MSA | 기각 — 네트워크 트랜잭션·운영 비용이 초기 OS에 과도 |

## 왜 이렇게 하나

- 재고 차감과 키 발급은 **불변식·실패 모드가 다름**. 한 모듈에 두면 CMS와 체크인이 다시 끊긴다 (정책서 PON).
- OTA 어댑터(`channel`)는 변경이 잦고, 객실 마스터(`property`)는 안정적 → 소유권·변경 빈도 분리.
- ADR-001 판매모드·오버북 게이트는 `inventory`에만 두어 **단일 진실**을 지킨다.
- 모듈식 모놀리스면 나중에 서비스 분리 시 모듈 경계가 그대로 후보가 된다.

## 아직 안 정한 것

- [ ] 모듈 간 이벤트(동기 facade vs 도메인 이벤트) 기본 패턴
- [ ] `channel`이 booking을 직접 만드는지, inventory 예약 홀드만 하는지
- [ ] 통합 테스트 경계: 모듈별 vs `app-api` 슬라이스만
- [ ] 패키지 접근 제어(Java module / ArchUnit) 도입 시점

## 결과 (구현에 미치는 영향)

- 루트 Gradle에 `app-api` + `modules/{common,property,inventory,booking,checkin,channel}` 를 둔다.
- 신규 기능은 위 표의 책임에 맞는 모듈에만 추가한다. “운영” 폴더를 만들지 않는다.
- 재고·오버북 로직은 `module-inventory` 밖(특히 channel·booking 컨트롤러)에 두지 않는다.
- 호텔형 객실 배정은 `module-checkin`, 특정방 즉시 배정은 `module-booking`.

## 참고

- 관련 ADR: [ADR-001 재고·오버부킹](./001-inventory-overbooking.md)
- 관련 PRD: [CMS·체크인 정책서](../prd/cms-checkin-core/policy.md)
- 외부/업계: 모듈식 모놀리스 → 필요 시 바운디드 컨텍스트별 서비스 분리
