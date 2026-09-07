# #135 self-assign 다중 담당자 배정 — QA 결과

PR #137 dev 병합 후, 실제 GitHub Actions 실행으로 검증. 이 워크플로우는 default 브랜치에
있는 버전으로만 실행되는 특성상, 병합 전에는 실제 동작 검증이 불가능해 QA를 병합 후에 진행함.

## 검증 시나리오 및 결과

### 1. 제3자가 이미 담당자인 상태에서 다른 사람이 /assign (핵심 회귀 시나리오)
- 이슈 #138에 협업자 `081114ysm`을 API로 먼저 배정
- `4n5rud`가 `/assign` 코멘트 작성
- self-assign 워크플로우 실행(run 34072328560, conclusion: success)
- 결과: 담당자가 `[081114ysm, 4n5rud]`로 정상 추가됨 — **수정 전이었다면 스킵됐을 케이스**,
  수정 후 정상 배정 확인

### 2. 본인이 이미 담당자인 상태에서 /assign 재입력
- 같은 이슈에서 `4n5rud`가 `/assign` 재입력
- 워크플로우 실행(run 34072375533, conclusion: success)
- 결과: 담당자 목록 변화 없음(중복 배정 안 됨), 이슈에
  `"@4n5rud님은 이미 이 이슈의 담당자로 배정되어 있습니다."` 안내 코멘트 정상 게시

### 3. (참고) 최초 배정 경로
- 이슈 #134에서 담당자가 0명인 상태에 `4n5rud`가 `/assign` → 정상 배정 (default 브랜치를
  main→dev로 바꾼 직후, 이 fix 이전 버전으로 검증한 것으로 기존 경로 회귀 없음 확인)

## 확인 안 된 부분
- Discord 알림(`DISCORD_ISSUE_WEBHOOK`) 실제 수신 여부는 채널 접근 권한이 없어 직접
  확인하지 않음. `run:` 스텝은 `continue-on-error: true`라 실패해도 워크플로우 전체
  conclusion은 success로 나오므로, 워크플로우 success만으로 알림 발송까지 보장되지 않음.
  코드 리뷰 문서(135-...-code-review.md)에서 스크립트 자체(jq 페이로드 생성)는 5개 분기
  전부 정적으로 검증됨.

## Critical/High/Medium/Low
없음 — 위 시나리오 모두 기대대로 동작함.
