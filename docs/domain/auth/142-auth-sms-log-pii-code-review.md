# #142 Aligo SMS 발송 실패 로그 전화번호 노출 — 코드 리뷰 결과

관련 기획서: [142-auth-sms-log-pii.md](./142-auth-sms-log-pii.md)

## 리뷰 범위/방법
- 대상: `git diff dev...HEAD` (`fix/#142-aligo-sms-log-pii` vs `dev`), 커밋 3개
  1. 기획서 추가 — `docs/domain/auth/142-auth-sms-log-pii.md`
  2. 로그 수정 — `src/main/java/com/remake/gone/sms/AligoSmsSender.java:43`
     (`log.error("Aligo SMS API 호출 실패: phoneNumber={}", phoneNumber, e)` →
     `log.error("Aligo SMS API 호출 실패", e)`)
  3. 테스트 추가 — `src/test/java/com/remake/gone/sms/AligoSmsSenderTest.java`
     (Logback `ListAppender` 기반, `GlobalExceptionHandlerTest`와 동일한 패턴)
- 방법: [code-review-isolation.md](../../rules/code-review-isolation.md)에 따라 구현 대화
  맥락 없이 diff와 기획서만 전달한 별도 에이전트에서 `code-review` 스킬(effort: medium)을
  실행. line-by-line / removed-behavior / cross-file tracer / reuse / simplification /
  efficiency / altitude / conventions 8개 각도로 점검하고, 후보를 실제 소스 대조로 검증.
- 확인한 것: 로그 오버로드 선택이 올바른지(`log.error(String, Throwable)`), 같은 파일 49번
  줄의 `result_code` 로그가 이번 변경과 무관한지, `phoneNumber` 문자열을 파싱하는 다른
  코드/문서가 있는지, 새 테스트가 실제로 검증하려는 것을 검증하는지.

## Critical
없음.

## High
없음.

## Medium
없음.

## Low

### 1. 🟢 Low — `ListAppender` 부착/해제 보일러플레이트가 `GlobalExceptionHandlerTest`와 완전히 중복됨

**문제**: `AligoSmsSenderTest.java:38-61`에 추가된
`logAppender` 필드 + `@BeforeEach`에서의 attach + `@AfterEach detachLogAppender()` +
`private Logger logger()` 4종 세트가,
`src/test/java/com/remake/gone/common/exception/GlobalExceptionHandlerTest.java:45-61`에
이미 있는 동일한 패턴(필드명·구조까지 거의 동일)을 그대로 복제한 것이다. 두 파일 모두
독립적으로 `ListAppender`를 생성/`start()`/`addAppender()`/`detachAppender()`한다. 이후
로그 캡처 방식에 문제가 생기거나(예: `additivity` 설정 때문에 이벤트가 중복 캡처되는 경우,
`ListAppender`를 `stop()`하지 않아 다음 테스트 클래스에 영향을 주는 경우) 개선이 필요해지면,
두 파일 중 한쪽만 고치고 다른 쪽을 놓치기 쉽다.

**해결 방안**:
1. 공용 JUnit5 확장(`@ExtendWith`) 또는 테스트 유틸 클래스로 추출한다 — 예:
   대상 로거 클래스를 파라미터로 받아 attach/detach를 대신 처리하는
   `LogCaptureExtension`. 장점: 중복이 완전히 사라지고 이후 로그 캡처 테스트가 늘어나도
   재사용 가능. 단점: 이번 이슈(#142, 로그 1줄 수정 + 검증 테스트 1건 추가)의 범위보다
   리팩토링 규모가 크고, 효과를 보려면 기존 `GlobalExceptionHandlerTest`까지 같이
   고쳐야 하므로 이번 PR 범위를 벗어난다.
2. 지금은 그대로 두고, 같은 패턴을 쓰는 세 번째 테스트 클래스가 생기는 시점에 추출한다
   (Rule of Three). 장점: 이번 PR을 기획서 범위(로그 1줄) 안에 유지할 수 있고 리스크가
   없다. 단점: 이미 두 곳에 동일 코드가 있어 "지금 추출해도 되는" 중복 임계값은 사실상
   넘은 상태이며, 추출을 미룰수록 세 번째 복제가 생길 가능성이 있다.

### 2. 🟢 Low — 새 테스트가 `test-convention.md`의 `@Nested` 그룹화 규칙을 따르지 않음(기존 파일 패턴을 답습)

**문제**: [test-convention.md](../../rules/test-convention.md)는 "메서드 단위로
`@Nested` 클래스 + `@DisplayName("메서드명")`으로 그룹화"를 요구한다.
`AligoSmsSenderTest.java`는 `send()` 메서드 하나만 테스트하는데도, 기존 3개 테스트
(`sendsSuccessfully`, `throwsWhenResultCodeNegative`, `throwsOnServerError`)부터
이미 `@Nested`로 묶이지 않고 flat `@Test`로 나열돼 있었다. 이번 diff는 그 위에 4번째
flat `@Test`(`doesNotLogPhoneNumberOnServerError`, 111-124번 줄)를 추가해 기존
컨벤션 미준수를 그대로 답습한다. 반대로 `GlobalExceptionHandlerTest.java`는
`handleException`/`handleMissingServletRequestParameter`/`handleAccessDenied` 등
메서드별로 정확히 `@Nested @DisplayName(메서드명)`을 지키고 있어(41-166번 줄),
같은 프로젝트 안에서 파일마다 컨벤션 적용 여부가 갈린다.

**해결 방안**:
1. 이번 PR에서 `AligoSmsSenderTest` 전체를 `@Nested @DisplayName("send")` 블록으로
   감싸도록 리팩토링한다. 장점: 컨벤션을 완전히 준수하고 `GlobalExceptionHandlerTest`와
   동일한 패턴으로 통일된다. 단점: 기획서에 명시된 범위("로그 메시지 1줄만 변경",
   테스트는 검증 케이스 1건 추가)를 벗어나는 리팩토링이 diff에 섞여, PR 리뷰/변경
   추적이 어려워진다(#142는 이슈 자체가 로그 노출 1건으로 좁게 정의됨).
2. 이번 PR은 그대로 두고, 기존 3개 테스트를 포함한 파일 전체의 `@Nested` 정리를
   별도 이슈(컨벤션 정리용 후속 이슈, 또는 #144와 함께)로 분리한다. 장점: #142 범위를
   "로그 1줄" 그대로 유지할 수 있어 기획서 원칙과 일관된다. 단점: 컨벤션 미준수 상태가
   당장 해소되지 않고 남는다.

## 결론
Critical/High/Medium 없음. 로그 오버로드 선택(`log.error(String, Throwable)`)이 올바르고,
같은 파일 49번 줄의 `result_code` 로그는 애초에 `phoneNumber`를 남기지 않아 이번 변경과
무관함을 확인했다. `phoneNumber=` 형식 로그를 파싱하는 다른 코드/모니터링 설정도 없다.
새 테스트(`doesNotLogPhoneNumberOnServerError`)는 `RestClientException` 경로에서 로그
이벤트가 정확히 1건만 발생한다는 것까지 검증하므로(49번 줄 로그로 새지 않음을 간접
확인), 검증 의도에 맞게 작성되어 있다. 기획서에 정의된 범위(로그 1줄 변경) 밖의 변경은
없다. Low 2건은 모두 스타일/컨벤션/중복 성격으로, 병합을 막을 이유는 아니며 다음 단계
(QA)로 진행해도 무방하다.
