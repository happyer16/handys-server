# adr-harness 사용 예시

## 부르기

- `adr-harness`, `ADR 쓰자`, `기술 결정 남겨`, `오버북 규칙 정리`

---

## 예시 1: 생성

**입력**
> 특정방이랑 호텔형 재고를 같은 모델로 짜면 안 될 것 같아. 오버북도 호텔형만.

**에이전트**
1. 기존 `wiki/decisions/` 확인
2. `wiki/decisions/001-….md` 초안 (또는 다음 번호)
3. Evaluator 채점
4. Accepted 시 `wiki/README.md` 인덱스 갱신

---

## 예시 2: 평가만

**입력**
> `wiki/decisions/001-inventory-overbooking.md` 평가해줘

채점표 + 개선 지시. 자동 수정은 요청 후.
