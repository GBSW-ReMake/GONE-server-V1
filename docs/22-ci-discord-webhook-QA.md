# #22 CI Discord 알림 개선 — QA/코드 리뷰 결과

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/22
관련 기획서: [22-ci-discord-webhook.md](./22-ci-discord-webhook.md)

## 자동 검증
- `npx js-yaml .github/workflows/ci.yml`로 YAML 문법 검증 통과, `checkstyle`/`build-and-test`
  두 job의 step 구성(이름/`if` 조건)이 의도대로 파싱됨을 확인.
- 애플리케이션 코드 변경이 없어 `./gradlew build`/`test`/`checkstyleMain` 대상은 아님(#22
  기획서에 명시된 대로 워크플로우 yaml만 수정).

## QA — 로컬에서 셸 로직 실측 검증

### 검증한 것
- **테스트 결과 집계 스크립트**: 더미 JUnit XML 2개(`tests="10" failures="1" skipped="2"`,
  `tests="5" errors="1"`)를 만들어 워크플로우와 동일한 `grep`/`awk` 파이프라인을 그대로 실행 →
  `11 passed, 2 failed, 2 skipped`로 정확히 집계됨을 확인(10+5=15건 중 실패/에러/스킵 제외한
  나머지가 정확히 11).
- **Checkstyle 위반 건수 집계**: 더미 checkstyle XML(`<error` 2건)로 동일하게 `VIOLATIONS=2`
  정확히 집계됨을 확인.
- **JSON payload 생성 + 인젝션 안전성**: PR 제목/커밋 메시지에 큰따옴표, 백틱, `$(rm -rf /)`
  같은 셸 명령 치환 구문이 섞인 값을 넣어 실제 `jq -n --arg ...` 파이프라인을 그대로 실행 →
  결과가 유효한 JSON으로 파싱되고(`jq .`로 확인), 문제의 문자열이 순수 데이터로만 들어가고
  실행되지 않음을 확인(`env:` 블록 경유 + `jq --arg`가 기획서에 적은 대로 동작).

### PR #23을 열어 실환경에서 재검증한 결과
로컬 검증 이후 실제로 PR #23을 열어 워크플로우를 여러 번 돌려봤고, 그 과정에서 로컬 검증으로는
못 잡는 문제 2건을 실제로 발견/수정했다.

- **1차 시도: 워크플로우 자체가 파싱조차 안 되고 0초 만에 실패.** "PR 제목/커밋 메시지를
  표현식으로 직접 끼워넣지 않는다"는 걸 설명하려고 **주석 안에 리터럴 `${{ }}`를 그대로
  적어놨는데**, GitHub Actions는 `run:` 스크립트 전체 텍스트(주석 포함)에서 `${{ }}`를 찾아
  표현식으로 파싱을 시도한다 — 즉 그 설명 주석 자체가 워크플로우를 깨뜨렸다. 로컬의
  `npx js-yaml` 검증은 범용 YAML 문법만 보기 때문에 이 GitHub Actions 전용 스키마 문제를 못
  잡았다. `actionlint`(GitHub Actions 전용 린터, 릴리스에서 다운로드해 로컬 실행)로 정확한
  줄/컬럼까지 특정해 수정. 이후 `actionlint`로 재검증 통과 확인.
- **2차: PR에 자동으로 붙은 Copilot 리뷰**가 실제 코드에서 2건을 더 찾음:
  1. Discord 알림 스텝이 `continue-on-error` 없이 실행돼서, `DISCORD_CI_WEBHOOK` 시크릿이
     없는 상황(fork PR 등)에서 알림 발송 자체가 실패하면 이미 통과한 빌드/테스트 결과까지
     job 실패로 뒤집을 수 있었음 → 알림 3개 스텝 모두 `continue-on-error: true` 추가.
  2. 테스트 결과 집계가 파일 전체를 `grep`해서, JUnit XML의 `<system-out>`에 캡처된 테스트
     콘솔 로그(Hibernate/Spring 로그 등) 안에 우연히 `tests="99"` 같은 패턴이 찍혀 있으면
     중복 집계될 수 있었음. 실제 프로젝트 테스트 출력 29개 파일을 전수 확인한 결과 현재는
     발생하지 않지만 잠재적 오탐이었음 → `<testsuite ` 여는 태그 줄로 먼저 좁힌 뒤 속성을
     추출하도록 변경. 로컬에서 결함 있던 원래 로직과 수정된 로직 양쪽에 "로그에 decoy
     `tests="99"`가 섞인" 더미 XML을 넣어 재현 → 수정 후에만 정확히 무시됨을 확인.
- **실제 Discord 알림 수신**: 최종 수정 반영 후 PR #23의 CI가 `checkstyle`(1m18s)/
  `build-and-test`(2m16s) 모두 정상 통과했고, 사용자가 Discord 채널에서 알림 수신을 직접
  확인함. `build-and-test` job의 실제 실행 로그에서도 `TEST_SUMMARY: 94 passed, 0 failed,
  0 skipped`, `PR_FIELD`/`SHORT_SHA`/`COMMIT_MSG`/`ELAPSED`가 전부 의도대로 채워졌고,
  curl이 payload(963 byte)를 전송해 Discord 표준 성공 응답(204 No Content, 0 byte 응답)
  패턴으로 완료됨을 로그로 확인.
- **`push` 이벤트 경로**: `github.event.head_commit.message`를 쓰는 쪽은 `dev`/`main`에 직접
  push될 때만 타므로, 이 부분은 PR 머지 이후에나 실환경에서 확인 가능(다만 `pull_request`
  경로와 로직 대부분을 공유하므로 리스크는 낮다고 판단).

## 코드 리뷰 (자체 점검)

### 확인한 항목
- `github.event.pull_request.title` 등 외부 입력 가능성이 있는 값을 `run:` 스크립트 본문에
  직접 `${{ }}`로 splice하지 않고 전부 `env:` 블록으로만 전달했는지 diff 재확인 — 두 job의
  "알림용 컨텍스트 수집" 스텝 모두 해당 패턴 준수(단, 주석에도 리터럴 `${{ }}`를 남기지 않도록
  추가로 주의 — 위 "실환경 재검증" 1차 시도 참고).
- Discord 알림 스텝에서 문자열 결합 대신 `jq -n --arg`만 사용해 JSON을 만드는지 확인 — 손으로
  문자열을 이어붙이는 부분 없음.
- 기존에 있던 `checkstyle-report`/`test-results` 아티팩트 업로드 스텝은 그대로 유지(변경 없음).

### 발견 및 수정 완료
- **Medium**: 알림 발송 실패가 CI 전체 결과를 뒤집을 수 있던 문제 → `continue-on-error` 추가로 수정.
- **Medium**: 테스트 콘솔 로그에 우연히 섞인 패턴으로 테스트 집계가 오염될 수 있던 잠재적 결함
  → `<testsuite ` 줄로 파싱 범위를 좁혀 수정.
- **Medium → 해결**: 워크플로우 파일 자체가 파싱 실패로 전혀 동작하지 않던 문제(주석 속
  리터럴 `${{ }}`) → 발견 즉시 수정, actionlint를 로컬 검증 루틴에 추가.

## 요약
| 항목 | 상태 |
|---|---|
| PR 컨텍스트(번호/제목/URL, 커밋, 아바타) | ✅ 완료, 실환경(PR #23)에서 검증 |
| 테스트 결과 요약 집계 | ✅ 완료, 실환경에서 `94 passed, 0 failed, 0 skipped` 정상 표시 확인 |
| Checkstyle 위반 건수 집계 | ✅ 완료, 로컬 더미 데이터로 검증(실패 케이스는 실환경 미발생) |
| `fields` 배열 포맷 | ✅ 완료, 실환경에서 Discord 채널 수신 확인 |
| 스크립트 인젝션 방지(`env:` + `jq`) | ✅ 완료, 적대적 입력으로 로컬 검증 |
| 실제 Discord 알림 수신 | ✅ 완료, PR #23에서 사용자가 직접 수신 확인 |
| 워크플로우 파싱 실패(주석 속 `${{ }}`) | ✅ 발견 및 수정 완료 (actionlint로 특정) |
| 알림 실패가 CI를 실패시키는 문제 | ✅ 발견(Copilot 리뷰) 및 수정 완료 |
| 테스트 집계 오탐 가능성 | ✅ 발견(Copilot 리뷰) 및 수정 완료 |
