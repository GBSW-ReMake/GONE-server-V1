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

### 검증하지 못한 것 (환경 제약)
- **실제 Discord 알림 수신**: `DISCORD_CI_WEBHOOK` 시크릿과 실제 GitHub Actions 러너 환경이
  필요해 로컬에서는 검증 불가능하다. 이 브랜치의 PR을 실제로 열면 그 자체가 `pull_request`
  이벤트로 워크플로우를 한 번 태워보는 첫 실환경 검증이 된다 — PR 생성 후 Discord 채널에서
  필드 포맷(브랜치/PR, 커밋, 테스트 요약, 소요 시간)이 의도대로 나오는지 확인 필요.
- **`push` 이벤트 경로**: `github.event.head_commit.message`를 쓰는 쪽은 `dev`/`main`에 직접
  push될 때만 타므로, PR 머지 이후에나 실환경에서 확인 가능.

## 코드 리뷰 (자체 점검)

### 확인한 항목
- `github.event.pull_request.title` 등 외부 입력 가능성이 있는 값을 `run:` 스크립트 본문에
  직접 `${{ }}`로 splice하지 않고 전부 `env:` 블록으로만 전달했는지 diff 재확인 — 두 job의
  "알림용 컨텍스트 수집" 스텝 모두 해당 패턴 준수.
- Discord 알림 스텝에서 문자열 결합 대신 `jq -n --arg`만 사용해 JSON을 만드는지 확인 — 손으로
  문자열을 이어붙이는 부분 없음.
- 기존에 있던 `checkstyle-report`/`test-results` 아티팩트 업로드 스텝은 그대로 유지(변경 없음).

### 이슈 없음
이번 조사에서는 심각도 있는 문제를 찾지 못했다.

## 요약
| 항목 | 상태 |
|---|---|
| PR 컨텍스트(번호/제목/URL, 커밋, 아바타) | ✅ 완료, YAML/셸 로직 로컬 검증 |
| 테스트 결과 요약 집계 | ✅ 완료, 더미 데이터로 로컬 검증 |
| Checkstyle 위반 건수 집계 | ✅ 완료, 더미 데이터로 로컬 검증 |
| `fields` 배열 포맷 | ✅ 완료 |
| 스크립트 인젝션 방지(`env:` + `jq`) | ✅ 완료, 적대적 입력으로 로컬 검증 |
| 실제 Discord 알림 수신 | ⚠️ Medium — 환경 제약으로 로컬 검증 불가, PR 생성 시 첫 실환경 검증 |
