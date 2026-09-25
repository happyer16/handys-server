# ADR 평가 — 004-inventory-redis-backup.md

| 차원 | 점수 | 한 줄 이유 |
|------|------|-----------|
| CX | 4/5 | 입실·보상 실패 비용과 SSOT 분리는 명확하나, DB-only 경합의 구체 시나리오·지연 수치는 한 문장 수준 |
| AL | 4/5 | A–F·T1–T3 표는 충분하나 C/D 기각이 정성 한 줄이라 latency·운영비 트레이드오프는 약함 |
| RC | 3/5 | hold 경로·Lua·P표는 있으나 confirm/release의 sold↔HELD 잠금 순서, “covering night” 술어, 다박 lock 순서가 비어 착수 시 빈칸 |
| CN | 4/5 | ADR-003 보완(non-Supersede)·001/002 인용·CMS vs 다이렉트 스코프는 맞음. 001 held TTL 체크박스 패치는 Accepted 시에만 닫힘 |
| IM | 4/5 | `unit_night` UNIQUE·Lua T1·`redis_mode`·docker conf·ArchUnit까지 방향 있음. hold 스키마·CAS SQL·covering 인덱스는 DDL 직전 공백 |
| OQ | 4/5 | 미결 체크·RPO/N회 가정·MVP=박 UNIQUE는 노출됨. orphan·PITR “채울 조건”은 여전히 느슨 |
| 평균 | 3.8 | |

**이전 치명(dual-write / 모드 미분기 / 카운터 TTL / FLUSH / RPO):** 해소.

## 치명 이슈
- (없음)

## 개선 지시 (우선순위)
1. confirm/release를 hold와 대칭으로 고정
2. `holds covering night` 술어 + 다박 lock 오름차순
3. rebuild CAS: rowcount=0이면 FLUSHDB 금지
4. max_sellable을 TX에 박제 (Redis cap=근사)
5. Accepted 시 ADR-001 TTL 체크박스 패치

**권고:** 평균 3.8 · 치명 없음 → **Accepted 가능**
