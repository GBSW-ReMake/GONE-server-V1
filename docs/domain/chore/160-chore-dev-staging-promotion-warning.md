# #160 dev → staging 프로모션 지연 경고 자동화 — 기획서

관련 이슈: [#160 dev → staging 프로모션 지연 경고 자동화 (GitHub + Discord)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/160)

## 개요/목적
`dev`에 PR이 계속 머지되는데 `staging`으로의 프로모션("dev → staging 배포" PR, 지금까지
#87/#140/#151/#154로 진행)이 늦어지면, 한 번에 몰아서 리뷰/QA해야 할 변경량이 너무
많아져 코드 리뷰가 제대로 돌아가지 않는다. 마지막 프로모션 이후 `dev`에 머지된 PR이
**5개** 쌓이면 GitHub 이슈 + Discord로 경고한다. 이번 범위는 **경고만**이며, 머지를
막는 강제성은 넣지 않는다(이슈 #160 본문에서 이미 확정).

애플리케이션 코드 변경은 없다(신규 엔드포인트 없음 — `api-design.md` 6원칙 검토는 해당 없음).

## 트리거
신규 워크플로우 `.github/workflows/promotion-warning.yml`, 두 개의 독립된 `on` 조건으로
동작한다.

1. **집계/경고**: `pull_request` → `types: [closed]`, `branches: [dev]`,
   `if: github.event.pull_request.merged == true` — `dev`에 PR이 머지될 때마다 실행.
2. **경고 해제**: `pull_request` → `types: [closed]`, `branches: [staging]`,
   `if: github.event.pull_request.merged == true && github.event.pull_request.head.ref == 'dev'`
   — "dev → staging 배포" PR이 머지될 때(즉 프로모션이 실제로 일어났을 때) 실행.

기존 `ci.yml`에 job을 추가하지 않고 별도 파일로 분리한다(`self-assign.yml`과 같은 관례 —
관심사가 다른 자동화는 별도 워크플로우 파일로 둔다).

## 임계치
**5** (`env.PROMOTION_WARNING_THRESHOLD`로 워크플로우 상단에 상수로 둔다 — 나중에 값만
바꿔 쓸 수 있게).

## 집계 로직 (job 1: `check-promotion-lag`)
1. `github.rest.pulls.list({ base: 'staging', state: 'closed' })`로 `staging`을 base로 하는
   닫힌 PR 중 `merged_at`이 있고 `head.ref === 'dev'`인 것만 걸러 `merged_at` 기준
   최신 1건을 "마지막 프로모션 시각"(`lastPromotionAt`)으로 잡는다. 하나도 없으면(첫
   프로모션 이전) `lastPromotionAt = null`로 두고 전체 이력을 대상으로 삼는다.
2. `github.rest.pulls.list({ base: 'dev', state: 'closed' })`로 `dev`를 base로 하는 닫힌
   PR 중 `merged_at`이 있고(`lastPromotionAt`이 있으면) `merged_at > lastPromotionAt`인
   것의 개수를 센다. 목록이 30건(API 기본 페이지 크기)을 넘을 수 있으니 필요한 만큼
   페이지네이션한다 — 정확한 페이지 처리 방식은 구현 단계에서 정한다.
3. 센 개수가 `PROMOTION_WARNING_THRESHOLD` 미만이면 아무것도 하지 않고 종료한다.
4. 임계치 이상이면 5단계(경고 발송)로 진행하되, **이미 열려 있는 경고 이슈가 있으면
   Discord는 다시 보내지 않고 이슈 본문의 누적 건수만 갱신한다** — 매 머지마다 Discord가
   반복 알림을 보내 스팸이 되는 것을 막기 위함(아래 "경고 발송" 참고).

## 경고 발송
- **추적용 라벨 `needs-promotion` 신규 생성** — 경고 이슈를 식별하는 용도로만 쓴다.
- `github.rest.issues.listForRepo({ labels: 'needs-promotion', state: 'open' })`로 이미 열린
  경고 이슈가 있는지 확인한다.
  - **없으면**: 새 이슈를 생성한다(`needs-promotion` + `chore` 라벨). 본문에 누적 PR
    개수, `dev`와 `staging`의 비교 링크(`{repo}/compare/staging...dev`)를 포함한다. 이어서
    Discord(`DISCORD_CI_WEBHOOK` 재사용, 기존 `ci.yml`/`self-assign.yml`과 동일하게
    `jq`로 embed payload를 만들어 `curl`로 전송)로도 알린다.
  - **이미 있으면**: 그 이슈에 코멘트로 최신 누적 건수만 갱신한다. Discord는 다시
    보내지 않는다.

## 경고 해제 (job 2: `close-promotion-warning`)
- "dev → staging 배포" PR(위 트리거 2번 조건)이 머지되면, `needs-promotion` 라벨이
  달린 열린 이슈를 전부 찾아 "프로모션 완료로 자동 종료합니다" 코멘트와 함께
  `state: closed`로 닫는다. 열린 이슈가 없으면 아무 것도 하지 않는다.

## 필요한 GitHub 권한
- `pull-requests: read` (PR 목록 조회)
- `issues: write` (이슈 생성/코멘트/종료)
- Discord 알림 스텝은 기존 관례대로 `continue-on-error: true`로 두어, 웹훅 실패가
  집계/이슈 처리 자체의 성공 여부를 뒤집지 않게 한다.

## 필요한 GitHub Secrets
| 이름 | 용도 |
|---|---|
| `DISCORD_CI_WEBHOOK` | 기존 체크스타일/빌드 실패 알림과 동일한 웹훅 재사용(신규 생성 안 함 — 결정 완료) |

## 영향 받는 기존 코드/설정
- `.github/workflows/promotion-warning.yml` (신규)
- 라벨 `needs-promotion` (신규 생성)
- 기존 `ci.yml`/`self-assign.yml`은 변경하지 않는다.

## 리스크 및 고려사항
- **PR 목록 API 페이지네이션**: `dev`/`staging`에 닫힌 PR이 많아지면(수백 건대) 한 번의
  `pulls.list` 호출로는 부족할 수 있다. `github.paginate` 사용을 기본으로 하되, 과도한
  API 호출을 막기 위해 "마지막 프로모션 시각보다 오래된 PR이 나오면 그 이후 페이지는
  가져오지 않는다"는 조기 종료 조건을 넣는 걸 권장한다(정확한 구현은 담당자 재량).
- **경고 스팸 방지**: 이미 열린 경고 이슈가 있으면 Discord를 재전송하지 않는 설계라,
  이슈가 실수로 닫히면(예: 사람이 직접 닫음) 다음 머지 때 새 이슈+Discord 알림이 다시
  나간다 — 의도된 동작이다(경고가 꺼졌으니 다시 켜지는 게 맞다).
- **강제성 없음**: 이 이슈는 경고만 다룬다. 경고를 반복해서 무시해도 `dev`로의 머지 자체는
  막히지 않는다 — 강제성이 필요해지면 별도 이슈로 진행한다(#160 본문에 이미 명시).
- **레이스 컨디션**: 짧은 간격으로 `dev`에 PR이 연달아 머지되면 `check-promotion-lag`
  job 여러 개가 거의 동시에 실행되어 "이미 열린 경고 이슈가 있는지" 확인 시점이 겹쳐
  중복 이슈가 생성될 수 있다. 발생 빈도가 낮고(현재 `dev` 머지 빈도상 흔치 않음) 결과도
  치명적이지 않아(중복 이슈 정리만 하면 됨) 이번 범위에서 동시성 제어(예: `concurrency`
  그룹)는 넣지 않는다(YAGNI) — 실제로 반복되면 후속 이슈로 보강한다.

## 완료 조건 (Definition of Done)
- `dev`에 프로모션 없이 PR이 5개 머지되면 `needs-promotion` 라벨이 달린 이슈가 생성되고
  Discord로 알림이 온다.
- 그 이후 추가로 머지되는 PR에 대해서는 이슈 본문의 누적 건수만 갱신되고 Discord는
  다시 오지 않는다.
- "dev → staging 배포" PR이 머지되면 열린 `needs-promotion` 이슈가 자동으로 닫힌다.
