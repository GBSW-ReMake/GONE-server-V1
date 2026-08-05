# #24 GlobalExceptionHandler 폴백 핸들러 예외 로깅 추가

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/24

## 개요/목적
`GlobalExceptionHandler.handleException(Exception e)`(그 위의 다른 `@ExceptionHandler`에도
안 걸리는 예상치 못한 예외의 최종 폴백)가 예외 `e`를 응답에도, 로그에도 전혀 쓰지 않고 그냥
`500 COMMON_007`만 반환한다. 실제로 예상 못 한 버그가 터져도 서버 어디에도 원인이 남지 않아
진단이 불가능하다(#20 QA 중 실제로 이 때문에 원인 파악이 지연된 사례 있음 — `docs/20-QA.md`
"조사 중 발견한 이슈" 참고). 이 폴백 핸들러 하나에만 로깅을 추가한다.

## 변경 전/후 동작 차이
**변경 전**: `handleException`이 `e`를 완전히 버림. 클라이언트 응답(`500 COMMON_007`)은
그대로지만, 서버 콘솔/로그 파일 어디에도 스택트레이스나 요청 정보가 남지 않는다.

**변경 후**: 클라이언트 응답은 동일하게 유지하되(계약 변경 없음), 처리 직전에
`log.error("{} {} 처리 중 예상치 못한 예외 발생", request.getMethod(), request.getRequestURI(), e)`
형태로 요청 메서드/경로 + 전체 스택트레이스를 ERROR 레벨로 남긴다. `HttpServletRequest`를
핸들러 메서드 파라미터로 추가해서 받는다(Spring MVC가 `@ExceptionHandler` 메서드에 자동
주입해주는 표준 방식, 별도 설정 불필요).

## 영향 받는 기존 코드/테스트
- `src/main/java/com/remake/gone/common/exception/GlobalExceptionHandler.java`
  - Lombok `@Slf4j` 어노테이션 추가(이 프로젝트에서 로거를 쓰는 첫 사례 — Spring Boot 기본
    포함인 SLF4J+Logback, Lombok도 이미 전역 의존성이라 새 의존성 추가 없음)
  - `handleException`에 `HttpServletRequest request` 파라미터 추가 + `log.error(...)` 한 줄
- 다른 `@ExceptionHandler`(`CustomException`, `MethodArgumentNotValidException`,
  `ConstraintViolationException`, `DataIntegrityViolationException`,
  `HttpRequestMethodNotSupportedException`)는 건드리지 않는다 — 이들은 "예상된" 비즈니스
  예외/4xx라 매번 ERROR로 로깅하면 오히려 노이즈가 된다(범위 밖, 아래 리스크 참고).
- 테스트: `GlobalExceptionHandlerTest`(신규) — `handleException`이 로그를 실제로 남기는지는
  전형적으로 `Logback ListAppender`를 붙여서 검증(로그 프레임워크에 새 의존성 추가 없이
  가능). 응답 바디/상태코드가 기존과 동일하게 유지되는지도 회귀 확인.

## 리스크 및 고려사항
- **다른 핸들러는 로깅하지 않기로 함**: `CustomException` 등은 이미 의도된 비즈니스 흐름(예:
  로그인 실패, 중복 가입 시도)이라 ERROR 레벨로 로깅하면 정상적인 사용자 행동이 매번 에러
  로그를 만들어내는 노이즈가 된다. 이번 이슈는 "예상치 못한" 예외 하나에만 집중한다. 나중에
  `DataIntegrityViolationException`(레이스 컨디션 발생 빈도 모니터링용)처럼 집계 목적의
  로깅이 필요해지면 별도 이슈로 논의.
- **로그에 담기는 정보의 민감도**: 스택트레이스에 요청 파라미터 값이 우연히 포함될 수는
  있지만(예외 메시지에 값이 섞여 들어간 경우), 지금 이 프로젝트엔 별도 로그 마스킹 체계가
  없다 — 이번 이슈 범위 밖으로 두되, 향후 실제 운영 로그를 보게 되면 재검토가 필요할 수 있음.
- **첫 로거 도입**: 이 프로젝트에 로거 사용이 전무했던 상태라, 로그 포맷/레벨 컨벤션을 이번
  건이 사실상 처음 정하게 된다. `@Slf4j` + SLF4J 플레이스홀더(`{}`) 스타일을 기본으로 삼는다.
