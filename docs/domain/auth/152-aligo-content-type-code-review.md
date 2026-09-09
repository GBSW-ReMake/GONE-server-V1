# #152 Aligo 응답 Content-Type 오탐 수정 — 코드 리뷰 결과

관련 기획서: [152-aligo-content-type.md](./152-aligo-content-type.md)

## 리뷰 범위/방법
- 대상: `fix/#152-aligo-content-type` 브랜치가 `dev`에서 분기된 이후 구현/테스트 커밋
  3개를 각각 `git show`로 개별 확인해 아래 2개 파일 외 변경이 없음을 확인했다:
  - `9f1a91c` fix(sms): #152 Aligo 응답 Content-Type과 무관하게 JSON 파싱 —
    `src/main/java/com/remake/gone/sms/AligoSmsSender.java` (23 insertions, 3 deletions)
  - `cf4d36f` test(sms): #152 Content-Type이 text/html인 Aligo 응답 재현 테스트 추가 —
    `src/test/java/com/remake/gone/sms/AligoSmsSenderTest.java` (43 insertions, 1 deletion)
  - `c6bf4dd` fix(sms): #152 코드 리뷰 지적(중복 에러 로그) 반영 —
    `src/main/java/com/remake/gone/sms/AligoSmsSender.java` (7 insertions, 3 deletions,
    이 문서의 "코드 리뷰 지적 사항" 1번 항목 반영)
  (이 worktree의 로컬 `dev` ref가 원격보다 크게 뒤처져 있어 `git diff dev...HEAD`는
  `origin/dev` 기준으로도 #144/#149 등 관련 없는 커밋 10여 개가 섞여 나온다 — 위 세 커밋
  각각의 diff로만 이슈 #152 범위를 확인했다.)
- 방법: 기획서와 diff만으로 독립적으로 점검했다.
  - 기획서 "변경 전/후" 코드 블록이 실제 diff와 일치하는지 대조.
  - `.body(JsonNode.class)` → `.body(String.class)` + `parseBody()` 변경이 Aligo API
    계약(엔드포인트, 요청 폼, `AuthErrorCode.SMS_SEND_FAILED`/502)을 그대로 유지하는지,
    호출부(`PhoneAuthService`)에 영향이 없는지 확인.
  - Jackson 3(`tools.jackson.*`) 환경에서 `ObjectMapper.readTree(String)` 실패 시 던지는
    예외 타입이 `catch (JacksonException e)`로 충분히 잡히는지, 프로젝트 내 다른
    Jackson 3 사용처(`NeisClient`, `RestAuthenticationEntryPoint`)와 비교해 확인.
    `build.gradle`의 Spring Boot 버전이 `4.1.0`으로 Jackson 3 계열임을 확인했다.
    `tools.jackson.core.JacksonException`은 Jackson 3에서 (Jackson 2의
    `JsonProcessingException`과 달리) unchecked 예외 계층의 루트이므로,
    `readTree(String)`이 던질 수 있는 파싱 실패 예외는 전부 이 타입 하나로 수렴한다 —
    별도 하위 타입을 놓칠 위험이 없다.
  - 새 테스트 3개가 실제로 수정 전 코드에서 실패했을 시나리오인지 diff 기준으로 추론:
    - `sendsSuccessfullyWhenContentTypeIsTextHtml`: 수정 전에는
      `.body(JsonNode.class)`가 `text/html` Content-Type에서 `UnknownContentTypeException`
      (→ `RestClientException` 하위)을 던져 `SMS_SEND_FAILED`로 귀결됐을 것이므로, 이
      테스트는 수정 전엔 실패하고 수정 후엔 통과한다 — 버그를 실제로 재현하는 케이스.
    - `throwsWhenContentTypeIsTextHtmlAndResultCodeNegative`,
      `throwsWhenBodyIsNotParsableJson`: 두 경우 모두 수정 전에도(경로는 다르지만)
      결과적으로 `SMS_SEND_FAILED`가 던져졌을 것이므로, 수정 전후 모두 통과하는
      회귀 방지용 케이스다. 기획서 "영향받는 기존 코드/테스트" 절도 이 두 케이스를
      "여전히 SMS_SEND_FAILED를 던진다"로 서술해, 버그 재현이 아니라 동작 불변 확인이
      목적임을 명시하고 있어 설계와 일치한다.
  - `JsonNode` import(`AligoSmsSender.java:16`)가 `parseBody()`의 반환 타입과 `body`
    지역 변수에 실제로 쓰이고 있어 미사용 import가 아님을 확인.
  - `docs/rules/code-style.md`, `docs/rules/test-convention.md`와 대조.
  - `./gradlew checkstyleMain checkstyleTest --rerun-tasks` 실행 — BUILD SUCCESSFUL
    (경고 0건).
  - `./gradlew test --tests com.remake.gone.sms.AligoSmsSenderTest --rerun-tasks` 실행 —
    BUILD SUCCESSFUL (신규 3개 + 기존 7개, 총 10개 전부 통과).
  - `code-review` 스킬 기반 독립 리뷰 에이전트를 별도로 실행(컨텍스트 격리 —
    구현 대화 이력 없이 diff와 기획서만 전달).

## 결과: 🟢 Low 1건, 그 외 없음

### 1. 🟢 Low — 응답 파싱 실패 시 ERROR 로그가 같은 실패에 대해 두 줄 남는다

**문제**: `AligoSmsSender.parseBody()`가 `readTree` 실패 시
`"Aligo SMS API 응답 파싱 실패"`를 예외와 함께 ERROR로 로깅하고 `null`을 반환한다
(`AligoSmsSender.java:74-77`). 이 `null`은 곧바로 `send()`의
`if (body == null || !body.hasNonNull("result_code"))` 분기로 들어가
`"Aligo SMS API 응답 바디 없음 또는 result_code 누락"`을 다시 ERROR로 로깅한다
(`AligoSmsSender.java:55-57`). 파싱 실패라는 같은 사고 하나에 ERROR 로그 2줄이 남아,
알람/로그 집계 관점에서 실제 장애 건수를 실제보다 부풀려 보이게 할 수 있다. 기능적
영향은 없다(둘 다 결국 `SMS_SEND_FAILED` 하나로 귀결되고, 기획서도 파싱 실패를 기존
`body == null` 경로로 흡수시키는 것을 의도된 설계로 명시하고 있다).

**해결 방안**:
1. `parseBody()`의 로그 레벨을 `WARN` 이하로 낮추거나, `parseBody()`에서는 로그를
   남기지 않고 호출부의 `body == null` 로그 한 줄에만 위임한다 — 코드 변경이 가장
   작지만, 파싱 실패와 "바디 없음/`result_code` 누락"이라는 서로 다른 원인을 로그
   한 줄로 뭉뚱그리게 돼 사후 디버깅 시 원인 구분이 어려워지는 트레이드오프가 있다.
   (예외 스택트레이스 자체는 `parseBody()` 쪽에만 있으므로, 완전히 없애면 파싱 실패의
   구체적 원인을 잃는다.)
2. 현재 상태를 유지한다 — 두 로그 모두 ERROR 레벨이 적절한 심각도이고(둘 다 실제
   SMS 발송 실패로 이어짐), 로그 볼륨 증가가 미미해(줄당 1회, 매 실패 요청당 최대
   2줄) 별도 조치 없이도 운영에 지장이 없다. 이번 이슈(#152)의 승인된 범위(Content-Type
   무관 파싱)에도 포함되지 않은 별개의 개선 사항이라, 필요해지면 후속 이슈로 분리하는
   편이 낫다.

이 프로젝트는 현재 옵션 2(현행 유지, 후속 이슈로 분리)를 채택해도 무방하다고 판단했으나,
보스 지시로 이번 이슈 안에서 바로 반영했다(`c6bf4dd`). `parseBody()`는 "바디 없음"과
"파싱 실패" 두 원인을 각각 한 줄씩 남기고, 호출부는 `body == null`이면 추가 로그 없이
곧장 실패 처리하며 `result_code` 누락만 별도로 한 줄 남기도록 분리해, 실패 원인별로
ERROR 로그가 정확히 한 줄만 남게 했다. 로그 원인 구분(파싱 실패 vs 바디 없음)은 유지된다.

## 세부 확인 근거
- **범위 일치**: 기획서가 명시한 파일(`AligoSmsSender.java`, `AligoSmsSenderTest.java`)
  외 변경이 없다. 새 HTTP 엔드포인트, 에러코드, `PhoneAuthService` 등 호출부 변경 없음 —
  기획서 "API 계약(엔드포인트, 요청 폼, 에러코드 체계)은 그대로 유지한다" 서술과 일치한다.
- **관찰 가능한 동작 불변**: `.retrieve()`의 4xx/5xx 상태 코드 처리(`HttpStatusCodeException`)는
  바디 타입(`String.class` vs `JsonNode.class`) 선택과 무관하게 상태 코드 단계에서
  먼저 일어나므로 영향받지 않는다(`throwsOnServerError` 기존 테스트가 그대로 통과해
  실증됨). 바디가 비어 있는 200 응답(`throwsWhenBodyIsEmpty`)도 `parseBody()`의
  `rawBody == null || rawBody.isBlank()` 방어 분기로 기존과 동일하게 `SMS_SEND_FAILED`로
  귀결된다(테스트로 재확인).
- **`result_code` 판정 로직 재사용**: `resultCode < 0` 분기(`AligoSmsSender.java:60-65`)는
  diff에서 전혀 손대지 않았다 — 파싱 방식만 바뀌었을 뿐 판정 로직은 기존 그대로다.
- **컨벤션 준수**:
  - 들여쓰기 2칸, import 알파벳 정렬, 100자 제한 등 `checkstyleMain`/`checkstyleTest`
    (`maxWarnings=0`) 통과로 확인.
  - 새 주석(`AligoSmsSender.java:50-52`)은 "왜 `String`으로 받아 직접 파싱하는지"를
    설명하는 WHY 주석이라 `code-style.md`의 "WHAT은 지양" 규칙에 부합한다. `private
    parseBody()`에는 Javadoc이 없는데, `code-style.md`가 Javadoc을 공개 API로 한정하므로
    문제없다.
  - 테스트는 기존 `@Nested(Send)` + 한글 `@DisplayName` 구조를 그대로 유지했고, 새
    케이스 3개의 `@DisplayName`이 검증 내용과 정확히 일치한다(`test-convention.md`).
    `MockRestServiceServer` 기반으로 실제 네트워크 호출 없이 검증하는 기존 패턴도
    동일하게 재사용했다.
  - #142(SMS 에러 로그 전화번호 노출 수정)와의 회귀 여부: 새로 추가된 파싱 실패 로그에
    `phoneNumber`나 원본 응답 바디 전체를 남기지 않는다(`log.error("Aligo SMS API
    응답 파싱 실패", e)`—메시지 자체엔 고정 문자열만, 예외 `e`에는 Aligo 응답 바디의
    파싱 실패 지점 스니펫만 포함되고 발신 대상 전화번호는 응답 바디에 애초에 담기지
    않는다) — 기존 PII 미노출 원칙과 충돌하지 않는다.
- **기획서-구현 일치**: 기획서 "변경 후" 코드 블록이 실제 diff와 동일하다(변수명, 분기
  조건, `parseBody()` 시그니처까지 일치). 실제 구현에는 기획서에 없던 WHY 주석
  (`AligoSmsSender.java:50-52`)이 추가됐는데, 로직 변경이 아니라 설명 보강이라 기획서
  서술과 모순되지 않는다.

## 참고: 독립 검증 에이전트 교차 확인
`docs/rules/code-review-isolation.md`에 따라 구현 대화 이력 없이 diff와 기획서만 전달한
별도 에이전트로 `code-review` 스킬 기반 독립 리뷰를 실행했다. 결과도 Medium 이상 findings
없음으로 일치했고, 같은 로그 중복(파싱 실패 시 ERROR 로그 2줄) 관찰을 Low로 독립적으로
지적해 이 문서의 1번 항목과 교차 확인됐다. 그 외에 범위 일치, checkstyle 통과,
`AligoSmsSenderTest` 10/10 통과, PII 미노출도 동일하게 확인했다.
