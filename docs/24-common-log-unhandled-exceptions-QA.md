# #24 GlobalExceptionHandler 폴백 핸들러 예외 로깅 추가 — QA/코드 리뷰 결과

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/24
관련 기획서: [24-common-log-unhandled-exceptions.md](./24-common-log-unhandled-exceptions.md)

## 자동 테스트
- `./gradlew build`, `./gradlew test`, `./gradlew checkstyleMain checkstyleTest` 모두 로컬 통과.
- 신규 테스트: `GlobalExceptionHandlerTest`(이 클래스 첫 테스트)
  - 응답 상태코드/바디(`500 COMMON_007`)가 기존과 동일하게 유지되는지 회귀 확인
  - Logback `ListAppender`를 붙여 `handleException` 호출 시 ERROR 레벨 로그가 정확히 1건
    남고, 메시지에 요청 메서드/경로가 포함되며, 예외가 스택트레이스와 함께 첨부되는지 확인

## QA — 실제 환경 검증

### 검증한 것
- 실제 로컬 서버(`./gradlew bootRun`)를 띄우고, `POST /api/v1/auth/login`에 **의도적으로
  깨진 JSON**(`{this is not valid json`)을 보내 `HttpMessageNotReadableException`을
  유발했다 — 이 예외는 `GlobalExceptionHandler`의 다른 특정 핸들러(`CustomException`,
  `MethodArgumentNotValidException` 등) 어디에도 해당하지 않아 폴백(`handleException`)으로
  떨어지는 실제 사례다.
  - 응답: 기존과 동일하게 `500` + `{"success":false,...,"code":"COMMON_007"}` 확인.
  - 서버 로그: `ERROR ... GlobalExceptionHandler : POST /api/v1/auth/login ...` 라인이
    새로 남고, 그 아래 `Caused by: tools.jackson.core.exc.StreamReadException: ...`로
    시작하는 전체 스택트레이스가 함께 출력됨을 확인 — 원래(#20 QA 때)는 이 상황에서 로그가
    전혀 안 남아 원인 파악이 안 됐던 문제가 실제로 해결됐음을 실환경에서 확인.
  - (참고) 이 세션의 터미널 콘솔 코드페이지 문제로 로그의 한글 부분이 화면에는 깨져
    보였다(`처리 중 예상치 못한 예외 발생` → 깨진 문자) — 이전 세션의 `[SMS]` 로그와 동일한,
    이 환경 고유의 표시 문제일 뿐 실제 로그 파일 인코딩이나 기능 문제는 아니다.
- 검증에 쓴 서버는 종료 후 포트(9091) 점유 프로세스까지 확인해서 정리했다.

### 검증하지 못한 것
- 없음. 이번 이슈는 로깅 추가 하나로 범위가 좁아 유닛 테스트 + 실서버 재현까지 전부 실환경
  검증까지 마쳤다.

## 코드 리뷰 (자체 점검)

### 확인한 항목 (문제 없음)
- 다른 `@ExceptionHandler`(`CustomException`, `MethodArgumentNotValidException`,
  `ConstraintViolationException`, `DataIntegrityViolationException`,
  `HttpRequestMethodNotSupportedException`)는 기획서대로 손대지 않았음(diff 재확인) —
  의도된 비즈니스 예외까지 ERROR로 로깅해 노이즈를 만드는 스코프 크립 없음.
- 클라이언트 응답 바디/상태코드는 기존과 완전히 동일 — 계약 변경 없음(테스트 + 실서버 둘 다
  확인).
- `HttpServletRequest`를 `@ExceptionHandler` 메서드 파라미터로 추가하는 방식은 Spring MVC가
  기본 지원하는 표준 패턴이라 별도 설정 불필요함을 실제 동작으로 확인.

### 이슈 없음
이번 조사에서는 문제를 찾지 못했다.

## 요약
| 항목 | 상태 |
|---|---|
| `handleException` 로깅 추가(요청 메서드/경로 + 스택트레이스) | ✅ 완료, 실서버 재현으로 검증 |
| 다른 핸들러 영향 없음(스코프 유지) | ✅ 완료, diff 확인 |
| 응답 계약 불변 | ✅ 완료, 테스트+실서버 둘 다 확인 |
