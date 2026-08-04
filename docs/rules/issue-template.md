# 이슈 템플릿 사용 규칙

- 신규 기능: `.github/ISSUE_TEMPLATE/feature.md`
- 버그: `.github/ISSUE_TEMPLATE/bug.md`
- `gh issue create --body-file`로 생성하고, 템플릿의 항목 구조는 임의로 삭제하지 않고 내용만 채운다.
- 제목은 한글로, 어떤 기능/버그인지 바로 파악되도록 간결하게 작성한다.
- 라벨은 아래 조합으로 지정한다.
  - 종류: `feature` / `bug` / `docs` / `refactor` / `chore` / `test`
  - 도메인 (해당 시): `domain:AUTH` 등. 없는 도메인이면 `domain:{도메인명}` 형식으로 새로 만들어 사용한다.
  - 우선순위: `priority:high` / `priority:medium` / `priority:low`
- 담당/논의가 필요한 이슈는 `needs-discussion`, 다른 작업에 막힌 경우 `blocked` 라벨을 추가로 지정한다.
