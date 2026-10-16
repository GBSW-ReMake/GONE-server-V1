# #160 dev → staging 프로모션 지연 경고 자동화 — 코드 리뷰 결과

관련 이슈: [#160](https://github.com/GBSW-ReMake/GONE-server-V1/issues/160)
관련 기획서: [160-chore-dev-staging-promotion-warning.md](./160-chore-dev-staging-promotion-warning.md)

리뷰는 구현 맥락을 공유하지 않은 별도 에이전트가 `origin/dev...HEAD` diff만 보고
`code-review` 스킬로 진행했다(`docs/rules/code-review-isolation.md` 절차).

**기획서 범위 준수**: 이탈 없음 확인. `ci.yml`/`self-assign.yml` 미수정, 신규 파일 2개만
추가, `permissions`/시크릿(`DISCORD_CI_WEBHOOK`)/임계치(5)/라벨(`needs-promotion`)이
기획서와 정확히 일치. 두 job의 `if` 조건(`base.ref == 'dev'` vs `base.ref == 'staging'`)도
상호배제되어 겹치거나 새는 경우 없음을 확인.

Critical/High 없음.

## 1. 🟡 Medium — `pulls.list` 페이지네이션이 무제한으로 전체 이력을 훑어, 레포가 커질수록 API 호출량이 계속 증가함

**문제**: `check-promotion-lag` job이 `dev`를 base로 하는 닫힌 PR 전체 이력을
`github.paginate`로 끝까지 가져온 뒤 필터링했다. 기획서의 "리스크 및 고려사항"에서 이미
조기 종료를 권장했지만 초기 구현에는 반영되지 않아, `dev` 머지가 쌓일수록 실행당 API
호출/시간이 선형으로 늘어난다.

**적용한 조치**: `dev` base PR 목록을 `sort: 'created', direction: 'desc'`로 페이지
단위(최대 100개)로 직접 가져오면서, 한 페이지 안의 PR이 전부 `lastPromotionAt`보다
먼저 생성됐으면 그 페이지를 마지막으로 조기 종료하도록 변경했다. **트레이드오프**: 아주
드물게 "오래전에 생성됐지만 최근에야 머지된" PR이 있으면 그 페이지 이후가 잘려 카운트에서
누락될 수 있다 — 이 팀의 빠른 머지 관행(며칠 내 머지)에서는 실질적 영향이 낮다고 판단해
받아들이기로 했다(코드 주석으로 명시).

## 2. 🟢 Low — 프로모션 이력이 전혀 없을 때(`lastPromotionAt == null`) 첫 실행에서 카운트가 과도하게 튈 수 있음

**문제**: `lastPromotionAt`이 없으면 count가 "`dev` 전체 머지 PR 수"가 되어, 프로모션
이력이 어떤 이유로든 유실되면(예: `staging` 재생성 등) 오탐성 경고가 즉시 발생할 수 있다.

**적용한 조치**: 이 경로를 탈 때 이슈 본문에 "프로모션 이력을 찾지 못해 dev 전체 이력을
기준으로 계산했습니다"라는 안내를 추가해, 실제로 이 분기를 타면 담당자가 원인을 바로
구분할 수 있게 했다. 근본적으로 이 분기 자체를 막지는 않는다 — 현재 레포는 프로모션
이력(#87/#140/#151/#154)이 있어 실제로 이 분기를 탈 가능성은 낮다고 판단했다.

## 3. 🟢 Low — `devPRs` 조회의 `sort: 'updated'` 옵션이 실질적으로 죽은 설정이었음

**문제**: 정렬 옵션이 `.filter()`로 전체를 훑는 기존 로직에 아무 영향을 주지 않았고,
`merged_at` 기준도 아니어서 오해의 소지가 있었다.

**적용한 조치**: 1번 항목의 조기 종료 로직 도입과 함께 `sort: 'created', direction:
'desc'`로 바꿔 실제로 조기 종료 판단에 쓰이도록 정리했다.
