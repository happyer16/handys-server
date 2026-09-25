## PRD 평가 결과

| 차원 | 점수 | 한줄 근거 |
|------|------|----------|
| SC 스콥 명확성 | 4/5 | 인/아웃·제외 사유·인접 의존은 있으나, ADR-002의 정산 모듈 YAGNI 대비 정산 소속·Mock PG/OTA 입금 stub 구현 경계가 비어 질문 유발 |
| FS 플로우 충분성 | 3/5 | 시나리오·일부 수식·표시 포맷은 있으나 Mock PG 계약, OTA payout→예약 분배식, 정산 라인 스키마, 환불 Intent 전이, cancel_event_id 생성 주체가 없어 질문 없이 구현 불가 |
| SD 상태 의존성 | 4/5 | 트리거→대상→범위와 DIRECT/OTA 조합표는 있으나, 환불 Intent·정산 Failed/Succeeded·TTL↔웹훅 레이스·PENDING 취소 조합이 매트릭스에 없음 |
| EC 엣지케이스 | 3/5 | nullable·핵심 TC는 있으나 TC-STL-DUP의 “Failed 또는 라인1” 모호, 정책 C 익월 음수 환불 조정·노쇼·24h 등호 경계가 PRD/TC에 없음 |
| ME 측정 가능성 | 4/5 | 핵심 KR·TC·가드레일·실패 시 재설계·이벤트표까지 있어 프로토타입 완료조건은 분명하나, 모니터링 목표(“분포만”)가 약함 |
| HA 가설 정합성 | 3/5 | 가설→멱등/confirmed/정산→지표·실패 액션은 맞으나, 정책 §5에서 취소·환불은 구현 2차인데 PRD가 MVP 스콥·TC에 편입하고, 익월 환불 recognition이 정책과 어긋남 |
| **평균** | **3.5** | |

평가일: 2026-09-25  
평가자: 별도 에이전트 (자기채점 아님)

### 개선 필요 항목 (3점 미만)

해당 없음 (3점 미만 차원 없음). 다만 FS·EC·HA가 경계선(3)이라 확정 전 보강이 필요함.

### 잘된 점

- 정책 성공정의·멱등 키 논리·결제 성공 시에만 confirmed·silent confirmed 금지가 PRD에 일관 반영됨
- held TTL 15분, 수수료 15%, 환불 24h 이진, policy_version `ps-v1` 등 수치가 닫혀 있음
- 스콥 아웃에 제외 사유가 있고, 실패 시 “UI로 덮지 않음”·키/상태머신 재설계가 명시됨
- ADR-001 오버북 규칙을 새로 발명하지 않고, held→confirmed를 결제와 묶어 ADR 미결을 제품적으로 닫으려 함

### 3점 이상이지만 개선 가능한 부분

- **FS(3):** Mock PG(동기/지연/중복 웹훅) 요청·응답·`pg_event_id` 계약; Admin OTA Posted 시 예약별 분배 입력/계산; `settlement` lines[] 필드; 환불 Intent 상태머신; `cancel_event_id` 발급 주체
- **EC(3):** DUP TC 기대값 단일화; 체크아웃월 포함 후 익월 전액환불 시 음수 조정 라인(정책 C); 노쇼; `cancel_at == checkin_at-24h`; charge Processing 중 취소
- **HA(3):** 정책 Prioritize와 맞춰 취소·환불을 MVP에서 빼거나, 정책을 “MVP에 전액환불 이진 포함”으로 개정해 문서 정합; 정산 수식을 정책 recognition과 동일하게 분기 기술
- **SC(4):** 정산을 `module-booking` 내 집계 vs 향후 분리 중 어디인지 ADR-002와 한 줄로 고정; 추가결제 “API만”의 엔드포인트 존재 여부
- **SD(4):** CANCELLED×CHARGE Succeeded×REFUND, EXPIRED×늦은 웹훅, 정산 Succeeded/Failed 재실행을 조합표에 추가
- **ME(4):** 데모/과제 완료 체크리스트를 이벤트·TC ID와 1:1로 묶고, 불일치 큐 모니터링에 최소 재현 기준을 수치화
