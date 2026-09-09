# #89 알리고(Aligo) SMS 연동 — 코드 리뷰 결과

관련 기획서: [89-auth-aligo-sms.md](./89-auth-aligo-sms.md)
리뷰 대상: `feat/#89-aligo-sms` 브랜치, `git diff dev..HEAD` (커밋 5개: `1040d55`, `41efd63`,
`b1e3ef0`, `5e1fdb0`, `4318497`)

## Critical/High/Medium/Low 없음

### 리뷰 범위/방법
- **기획서 대조**: [89-auth-aligo-sms.md](./89-auth-aligo-sms.md)를 먼저 읽고, 기획서에
  명시된 코드 블록(`AligoSmsSender`, `AligoProperties`/`AligoConfig`)과 실제 diff를 줄
  단위로 대조했다. 패키지 위치(`sms` 최상위 vs `sms.config`), 클래스 분리(레코드/설정 클래스를
  기획서는 한 코드 블록에 같이 보여줬지만 실제로는 Java 규칙상 별도 파일로 분리 — 문제
  아님), 이동 대상(`SmsSender`/`ConsoleSmsSender`)이 모두 기획서 범위와 일치함을 확인했다.
- **컨벤션 대조**: [code-style.md](../../rules/code-style.md),
  [test-convention.md](../../rules/test-convention.md),
  [commit-convention.md](../../rules/commit-convention.md),
  [sentence-refinement.md](../../rules/sentence-refinement.md) 원칙 6을 기준으로 새/수정
  파일을 검토했다.
- **기존 패턴 정합성**: `src/main/java/com/remake/gone/neis/`(`NeisClient`, `NeisConfig`,
  `NeisProperties`, `NeisErrorCode`)와 `NeisClientTest`를 나란히 놓고, `AligoSmsSender`/
  `AligoConfig`/`AligoProperties`/`AligoSmsSenderTest`가 동일한 구조·네이밍·예외 처리
  패턴을 따르는지 확인했다. 패키지 서브구조(`sms` 최상위에 `AligoSmsSender`, `sms.config`에
  `AligoProperties`/`AligoConfig`)가 `neis`/`neis.config`와 정확히 대응한다.
- **CI/CD 3지점 확인**: `.github/workflows/ci.yml`의 `git diff`를 직접 읽고, 기획서가 요구한
  세 지점(① `deploy-staging` 잡 상단 `env:` 블록, ② `appleboy/ssh-action`의 `envs:` 콤마
  목록, ③ `.env` 생성 heredoc 본문 + `SPRING_PROFILES_ACTIVE=staging` 신규 추가)이 모두
  빠짐없이 반영됐음을 확인했다. `build-and-test` 잡의 `ALIGO_*` 더미 값도 추가돼 있다.
- **`SPRING_PROFILES_ACTIVE` 실제 소비 경로 확인**: `deploy/docker-compose.dev.yml`의 `app`
  서비스가 `env_file: .env`로 `.env` 전체를 컨테이너 환경변수로 주입하고, 다른 곳에서
  `SPRING_PROFILES_ACTIVE`를 하드코딩/오버라이드하지 않음을 확인했다 — CI 수정이 실제로
  `staging` 프로필을 활성화시키는 경로까지 끊기지 않는다.
- **정적 검증 실행**: `./gradlew checkstyleMain checkstyleTest` 통과(경고 0건,
  `maxWarnings = 0` 기준 충족). 100자 제한은 한글이 섞인 줄(예: `AligoSmsSender.java`
  20~21행, `AligoSmsSenderTest.java` 25행/44행)이 있어 바이트 수 기준으로는 100자를
  넘어 보이지만, PowerShell로 유니코드 문자 수를 직접 세어 실제로는 100자를 넘지 않음을
  확인했다(Checkstyle의 `LineLength`는 문자 수 기준).
- **테스트 실행**: `./gradlew test --tests "com.remake.gone.sms.*" --tests
  "com.remake.gone.auth.service.PhoneAuthServiceTest"`를 `ALIGO_*`/`NEIS_*` 더미 값과 함께
  실행해 전부 통과함을 확인했다(`AligoSmsSenderTest` 3건, `PhoneAuthServiceTest` 7건, 실패
  0건).
- **범위 이탈 여부**: `git diff --stat`으로 변경 파일 11개를 전수 확인했다 —
  `AuthErrorCode`(`SMS_SEND_FAILED` 추가), `PhoneAuthService`/`PhoneAuthServiceTest`(import
  경로 변경 + 오해 소지 있던 인라인 주석 수정), `sms` 신규/이동 파일 6개, `ci.yml`, 기획서
  문서. 엔드포인트·요청/응답 스키마·DB 스키마 변경은 없으며, 기획서가 "이번 범위에 넣지
  않는다"고 명시한 `conduct` 도메인 연동 코드도 포함되지 않았다. 기획서에서 결정한 범위를
  벗어난 변경은 없다.

### 확인한 세부 사항(문제로 이어지지 않음, 참고용)
- `PhoneAuthService.sendVerificationCode`의 인라인 주석이 `// local 전용 로그에 인증번호
  뿌리기`에서 `// 구현체에 따라 콘솔 로그(dev) 또는 실제 SMS 발송(dev 외)`로 수정됐다 —
  기획서가 지적한 "더 이상 사실이 아닌 주석" 문제가 정확히 해결됐고, sentence-refinement.md
  원칙 6("예외적인 동작·제약 조건은 반드시 명시한다")에도 부합한다.
- `AligoSmsSender.send`의 실패 처리가 `RestClientException`(네트워크/HTTP 레벨)과
  `result_code < 0`(비즈니스 레벨) 두 갈래 모두 동일하게 `CustomException
  (AuthErrorCode.SMS_SEND_FAILED)`로 통일돼 있다 — `NeisClient.fetch`가 동일하게
  `RestClientException`과 `RESULT.CODE` 에러를 하나의 `EXTERNAL_API_ERROR`로 묶는 것과
  같은 패턴이며, `PhoneAuthService`가 `RuntimeException`만 잡아 쿨다운을 되돌리는 기존
  로직과도 맞물려 정확히 동작한다.
- `body.path("result_code").asInt()`는 응답에 `result_code` 필드가 없으면 기본값 0(성공
  취급)을 반환한다. 이론적으로는 응답 스키마가 깨졌을 때 실패를 놓칠 수 있는 지점이지만,
  `NeisClient.parseRows`도 `RESULT` 노드 부재/필드 누락을 동일한 방식(암묵적 기본값)으로
  처리하고 있어 이 프로젝트에 이미 확립된 패턴을 그대로 따른 것이다. 알리고 공식 API는
  `result_code`를 항상 포함하므로 실제로 발생할 시나리오가 아니라고 판단해 별도 이슈로
  분리하지 않았다.
