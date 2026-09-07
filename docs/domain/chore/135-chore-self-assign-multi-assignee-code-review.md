# #135 self-assign 다중 담당자 배정 — 코드 리뷰 결과

리뷰 대상: `fix/#135-self-assign-multi-assignee` 브랜치, `.github/workflows/self-assign.yml`
리뷰 방법: 컨텍스트가 격리된 code-reviewer 에이전트에게 위임. YAML 파싱, 임베디드
github-script JS `node --check`, `run:` 블록 `bash -n`, `ASSIGN_STATUS` 5개 분기 전부
실제 실행해 jq 페이로드 생성 확인, 레포 전체 `EXISTING_ASSIGNEES` 잔존 참조 grep을 수행함.

## 요약
Critical/High 없음. Medium 3건 중 이번 diff가 만든 회귀 2건은 반영 완료. 나머지 1건과
Low 6건은 기존부터 있던 이슈이거나 이번 수정 범위 밖이라 별도 이슈로 분리함(아래 참고).

## 반영 완료

### 1. 🟡 Medium — Discord 임베드 필드 이름 중복 (이번 diff가 만든 회귀)

**문제**: skipped 분기의 `DETAIL_NAME`을 기존 `"기존 담당자"`에서 `"요청자"`로 바꾸면서,
이미 하드코딩된 `{name: "요청자", value: "@$actor"}`(self-assign.yml:126)와 이름이
겹쳐 같은 임베드에 "요청자" 필드가 두 번 나타남.

**해결 방안**: DETAIL_NAME을 `"배정 상태"`로, DETAIL_VALUE를 `"이미 이 이슈의 담당자임"`으로
바꿔 124번째 줄 필드와 겹치지 않게 함. (반영: `04638fa`)

### 2. 🟡 Medium — 스킵 분기의 `createComment`가 try/catch 밖에 있어 실패 시 알림 자체가 생략됨

**문제**: `createComment`가 던지면(이슈 잠김, secondary rate limit 등)
`core.exportVariable('ASSIGN_STATUS', 'skipped')`에 도달하지 못해 `ASSIGN_STATUS`가
비어버리고, 알림 스텝의 `*)` 분기가 `exit 0`으로 조용히 끝나 Discord 알림이 아예 안 감.

**해결 방안**: `exportVariable` 호출을 `createComment` 호출 앞으로 옮김 — 코멘트 작성
성공 여부와 무관하게 배정 판정(스킵) 자체는 이미 끝난 상태이므로 상태값을 먼저 확정.
(반영: `04638fa`)

## 별도 이슈로 분리 (이번 PR 범위 밖)

### 3. 🟡 Medium — `failed` 상태 메시지가 "권한 없음"으로 원인을 단정
GitHub은 이슈당 담당자 최대 10명 제한이 있고 초과분도 조용히 무시한다. 다중 배정 경로가
열린 지금은 이 상한 초과도 `failed`의 실제 발생 가능한 원인이 되는데, 메시지는 "레포
write 권한 없음 등"으로 고정되어 있어 오진단 소지가 있음. `failed`일 때 이슈 코멘트가
전혀 남지 않는 것도 함께 검토 필요.

### 4~9. 🟢 Low (전부 기존 이슈이거나 이번 변경과 무관)
- `/assign @다른사람` 시 코멘트 작성자가 배정됨(경고 없음) — self-assign 설계상 의도된
  동작이나 다중 담당자 허용 후 혼동 가능성 커짐
- 로그인 비교 대소문자 구분 (`a.login === commenter`)
- payload 스냅샷 staleness로 인한 중복 알림 가능성 (연속 `/assign` 시)
- Discord 임베드 마크다운 링크 스푸핑 (이슈 제목에 마크다운 삽입 가능)
- 퍼블릭 레포 + `issue_comment` 트리거로 인한 Discord 웹훅 알림 스팸 벡터 (권한 상승 없음)
- `ERROR_MESSAGE` 길이 무제한 (Discord 필드 1024자 제한 초과 시 알림 유실)

이 중 우선순위가 있다고 판단되면 후속 이슈로 등록.
