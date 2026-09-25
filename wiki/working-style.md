# 일하는 방식

- **갱신:** 2026-09-25

## 세션 아카이브 → 나중에 회고

결정은 ADR/wiki에, 세션 흐름은 `sessions/`에 남긴다.

- **매번 wrapup은 지금 필수가 아님.** 속도·집중이 우선일 때는 건너뛰어도 된다.
- 대신 **모아 두기**: 의미 있는 세션이나 결정이 쌓인 구간에서 `handys-session-wrapup`으로 아카이브를 남긴다.
- **회고는 나중에 묶어서** 한다. `sessions/` + ADR의 “잘 된/아쉬운 판단”을 재료로 패턴·갭을 본다.
- wrapup을 돌릴 때: 요약 → wiki 고정(필요할 때만) → `sessions/YYMM/YYMMDD-slug.md` 저장. 완벽보다 일관성.

스킬: [`.cursor/skills/handys-session-wrapup`](../.cursor/skills/handys-session-wrapup/SKILL.md)

## 지식은 어디에

| 종류 | 위치 |
|------|------|
| 제품·도메인 컨텍스트 | `wiki/` |
| 기술 결정 | `wiki/decisions/` (ADR) |
| 제품 기획 | `wiki/prd/` |
| 세션 기록·회고 재료 | `sessions/` |

채팅에만 두지 말고, **다시 쓸 결정·맥락은 wiki에 고정**한다. wrapup이 그 트리거다.
