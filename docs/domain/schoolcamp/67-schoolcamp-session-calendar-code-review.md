# #67 스쿨캠핑 세션 등록 + 캘린더 조회 API — 코드 리뷰 결과

관련 기획서: [67-schoolcamp-session-calendar.md](./67-schoolcamp-session-calendar.md)

## 리뷰 범위/방법
- 대상: `src/main/java/com/remake/gone/schoolcamp/`, `src/test/java/com/remake/gone/schoolcamp/`,
  `src/main/resources/db/migration/V11__add_schoolcamp_session.sql` (`git diff
  origin/dev...feat/#67-schoolcamp-session-calendar`)
- `docs/domain/schoolcamp/1_schoolcamp-domain.md` 마크다운 변경분은 사전 승인된 기획 문서로 간주하고
  줄 단위 재검토 대상에서 제외했다.
- 기획서(`67-schoolcamp-session-calendar.md`) 전체와 대조해 범위 일치 여부(`SCHOOLCAMP_010` 미생성 포함)를
  확인했고, `docs/rules/code-style.md`, `docs/rules/test-convention.md`, `docs/rules/sentence-refinement.md`
  원칙 6을 기준으로 코드/주석 스타일을 확인했다.
- `OutingController`, `GlobalExceptionHandler`, `CommonErrorCode`, `outing`의 `*AuthorizationTest` 클래스 등
  기존 컨벤션과 직접 비교했고, `Jsr310DateTimeFormatAnnotationFormatterFactory`(spring-context 7.0.8, 이
  프로젝트가 쓰는 Spring Boot 4.1.0 기준)의 바이트코드를 확인해 `@DateTimeFormat` + `YearMonth` 바인딩이
  실제로 지원됨을 검증했다.

## 발견 사항

### 1. 🟠 High — `POST /api/v1/school-camps`가 기획서에 명시된 `201 Created` 대신 `200 OK`를 반환함

**문제**: `SchoolCampController.registerCampDates`(`src/main/java/com/remake/gone/schoolcamp/controller/SchoolCampController.java:37-44`)에는 `@ResponseStatus(HttpStatus.CREATED)`가 없다. 이 메서드는 `ResponseEntity`가 아니라 `ApiResponse<...>`를 직접 반환하므로, `@ResponseStatus`가 없으면 Spring MVC가 기본값인 `200 OK`로 응답한다. 그런데 기획서(`67-schoolcamp-session-calendar.md:74`)는 이 엔드포인트의 응답을 "**응답** (`201 Created`)"로 명시하고 있고, 같은 저장소에서 리소스를 새로 만드는 POST 엔드포인트인 `OutingController.applyOuting`(`src/main/java/com/remake/gone/outing/controller/OutingController.java:54-55`)은 이미 `@ResponseStatus(HttpStatus.CREATED)`를 붙여 이 관례를 지키고 있다. `SchoolCampControllerTest`의 성공 등록 테스트(`callsServiceWithRequestedDates`, `src/test/java/com/remake/gone/schoolcamp/controller/SchoolCampControllerTest.java:47-58`)는 컨트롤러 메서드를 직접 호출해 검증하기 때문에 MockMvc를 거치지 않고, 실제 HTTP 상태 코드는 어떤 테스트도 확인하지 않는다.

**해결 방안**:
1. `@PostMapping` 위에 `@ResponseStatus(HttpStatus.CREATED)`를 추가한다. `OutingController.applyOuting`과 완전히 같은 패턴이라 일관성이 가장 높고 수정 범위도 한 줄로 끝난다.
2. 반환 타입을 `ResponseEntity<ApiResponse<List<SchoolCampSessionResponse>>>`로 바꾸고 `ResponseEntity.status(HttpStatus.CREATED).body(...)`로 명시한다. `PhoneAuthController`가 쓰는 패턴이라 저장소 내 선례는 있지만, 같은 컨트롤러 안의 `getCalendar`(그대로 `ApiResponse<...>` 반환)와 반환 타입 스타일이 달라져 클래스 내부 일관성이 떨어진다.

어느 방안이든 MockMvc로 `status().isCreated()`를 확인하는 테스트를 함께 추가해야 회귀를 막을 수 있다.

### 2. 🟠 High — 달력상 존재하지 않는 날짜(예: `"20261332"`)를 등록 요청하면 `400`이 아니라 `500`이 반환됨

**문제**: `RegisterSchoolCampDatesRequest`(`src/main/java/com/remake/gone/schoolcamp/dto/RegisterSchoolCampDatesRequest.java:16-18`)의 `@Pattern(regexp = "^\\d{8}$")`는 문자열이 숫자 8자리인지만 검사하고, 그 값이 실제 달력에 존재하는 날짜인지는 검사하지 않는다. 예를 들어 `"20261332"`(13월)나 `"20260230"`(2월 30일)은 이 정규식을 통과한다. 통과한 값은 `SchoolCampService.registerCampDates`(`src/main/java/com/remake/gone/schoolcamp/service/SchoolCampService.java:33`)의 `LocalDate.parse(date, YMD_FORMATTER)`에서 `DateTimeParseException`(unchecked)을 일으킨다. `GlobalExceptionHandler`(`src/main/java/com/remake/gone/common/exception/GlobalExceptionHandler.java`)에는 이 예외를 처리하는 핸들러가 없어, 259번 줄의 `handleException` 폴백으로 떨어져 `500 Internal Server Error`와 함께 `ERROR` 레벨 스택트레이스가 로그에 남는다. 기획서(`67-schoolcamp-session-calendar.md:88`)는 "형식 오류는 Bean Validation 공통 처리로 `400`"이라고 전제하지만, `@Pattern`만으로는 이 전제가 실제로 보장되지 않는다. `MethodArgumentTypeMismatchException`/`NoResourceFoundException` 핸들러가 각각 #41에서 추가된 이유(GlobalExceptionHandler.java:203-246 Javadoc 참고)도 정확히 같은 종류의 결함, 즉 "클라이언트 입력 오류가 500으로 새는 것"을 막기 위해서였다.

**해결 방안**:
1. `SchoolCampService.registerCampDates`의 `LocalDate.parse` 호출을 감싸 `DateTimeParseException` 발생 시 `SchoolCampErrorCode.INVALID_CAMP_DATE`(400)로 변환한다. 새 에러 코드를 만들지 않아 기획서의 "`SCHOOLCAMP_010`을 만들지 않는다"는 결정과 충돌하지 않는다. 다만 "요일이 금/토/일"이라는 메시지가 "날짜 자체가 존재하지 않음"이라는 다른 원인을 정확히 설명하지 못한다.
2. `GlobalExceptionHandler`에 `@ExceptionHandler(DateTimeParseException.class)`를 추가해 `CommonErrorCode.INVALID_REQUEST`(400, `COMMON_001`)로 매핑한다. 기획서가 원래 기대한 "`COMMON_001`로 충분하다"는 전제를 코드로 실제 보장하고, 바로 위에 있는 `MethodArgumentTypeMismatchException` 핸들러와 대칭을 이룬다. 다만 이후 다른 도메인이 같은 예외를 다른 의미로 쓰고 싶을 때 전역 처리가 영향을 줄 수 있다(현재는 해당 사례 없음).
3. `@Pattern` 정규식을 실제 달력 유효성까지 검사하는 커스텀 Bean Validation 애노테이션으로 교체한다. 가장 근본적인 해결이지만, 이 저장소에 커스텀 검증 애노테이션 선례가 아직 없어 새 패턴을 도입하는 비용이 가장 크다.

### 3. 🟡 Medium — 같은 요청 안에서 날짜가 중복되면 도메인 에러 코드(`SCHOOLCAMP_006`) 대신 범용 `COMMON_006`이 반환됨

**문제**: `SchoolCampService.registerCampDates`(`src/main/java/com/remake/gone/schoolcamp/service/SchoolCampService.java:37-39`)의 중복 검사는 `sessionRepository.existsByCampDateIn(dates)`만 수행한다. 이는 DB에 이미 저장된 날짜와의 중복만 확인하고, 요청 리스트 자체에 같은 날짜가 두 번 들어있는 경우(예: `campDates: ["20260406", "20260406"]`)는 걸러내지 않는다. 이 경우 두 날짜 모두 `SchoolCampSession` 엔티티로 만들어져 `saveAll`로 넘어가고, `school_camp_session.camp_date UNIQUE` 제약(`V11__add_schoolcamp_session.sql:4`)에서 두 번째 삽입이 실패해 `DataIntegrityViolationException`이 발생한다. `GlobalExceptionHandler.handleDataIntegrityViolation`(153번 줄)이 이를 처리해 상태 코드는 `409`로 맞게 나오지만, 코드/메시지는 `SchoolCampErrorCode.CAMP_DATE_ALREADY_REGISTERED`(`SCHOOLCAMP_006`, "이미 등록된 날짜입니다")가 아니라 범용 `CommonErrorCode.CONFLICT`(`COMMON_006`, "이미 존재하는 리소스입니다")로 나온다. `@Transactional`이 걸려 있어 실제 데이터가 부분 저장되지는 않으므로 데이터 정합성 문제는 없다.

**해결 방안**:
1. `registerCampDates` 앞부분에서 `dates`를 `Set`으로 변환한 크기와 원본 리스트 크기를 비교해 요청 내부 중복을 확인하고, 다르면 `SchoolCampErrorCode.CAMP_DATE_ALREADY_REGISTERED`를 던진다. 기존 에러 코드를 그대로 재사용해 추가 코드 없이 해결되고, DB 왕복 전에 조기 실패하므로 더 빠르다.
2. 현재 동작(DB 유니크 제약 + 범용 `409`)을 그대로 두고 알려진 제약으로 문서화만 한다. 구현 비용은 0이지만, 관리자가 같은 날짜를 실수로 두 번 입력했을 때 응답 메시지만으로는 원인을 정확히 알기 어렵다.

### 4. 🟡 Medium — `ADMIN`이 아닌 계정의 `POST` 접근 거부가 어떤 테스트로도 검증되지 않음

**문제**: 기획서(`67-schoolcamp-session-calendar.md:194`) 테스트 절은 `POST /api/v1/school-camps`에 대해 "`ADMIN` 아닌 계정 접근 거부"를 요구하지만, 이 diff에는 대응하는 테스트가 없다. `SchoolCampControllerTest`(`src/test/java/com/remake/gone/schoolcamp/controller/SchoolCampControllerTest.java:24`)는 `@WebMvcTest(SchoolCampController.class)` + `@AutoConfigureMockMvc(addFilters = false)`를 쓰는데, `addFilters = false`는 Spring Security 필터 체인 자체를 MockMvc에서 제거하므로 `@PreAuthorize`가 실제로 평가되는지는 전혀 검증하지 못한다. 이 저장소는 이 한계를 이미 인지하고 있으며, `OutingApproveAuthorizationTest`(`src/test/java/com/remake/gone/outing/controller/OutingApproveAuthorizationTest.java:18-22`)의 Javadoc이 "`@WebMvcTest(addFilters = false)` 슬라이스는 인가 자체를 검증할 수 없다"고 명시하고, 그 대안으로 `@SpringBootTest` + 전체 필터 체인을 쓰는 `*AuthorizationTest` 클래스를 엔드포인트마다 별도로 둔다(`OutingApproveAuthorizationTest`, `OutingRejectAuthorizationTest`, `OutingReceivedAuthorizationTest`, `OutingMyRequestsAuthorizationTest`, `OutingDetailAuthorizationTest`). `schoolcamp`에는 이 패턴에 대응하는 테스트가 없어, `@PreAuthorize("hasRole('ADMIN')")`가 실수로 빠지거나 메서드 시큐리티 설정이 깨져도 테스트 스위트가 조용히 통과한다.

**해결 방안**:
1. `SchoolCampAuthorizationTest`를 `outing`의 `*AuthorizationTest` 클래스와 동일한 패턴(`@SpringBootTest` + `@AutoConfigureMockMvc`, `JwtProvider`로 `STUDENT`/`TEACHER` 역할 토큰을 발급해 `403` 확인)으로 추가한다. 저장소에 이미 있는 선례를 그대로 따르는 것이라 리뷰 부담이 적고, 이후 다른 도메인에도 같은 패턴을 계속 쓸 수 있다.
2. 10단계 QA에서 실서버로 `STUDENT`/`TEACHER` 토큰을 직접 발급해 수동 재현하고 QA 문서에 결과를 남기는 것으로 대신한다. 구현 비용은 낮지만, 이후 회귀가 생겨도 자동으로 잡히지 않는다.

### 5. 🟢 Low — `SchoolCampCalendarResponse`의 Javadoc `@param` 컬럼 정렬이 `applicantDisplayName`에서만 어긋남

**문제**: `SchoolCampCalendarResponse`(`src/main/java/com/remake/gone/schoolcamp/dto/SchoolCampCalendarResponse.java:8-13`)는 `sessionId`/`campDate`/`status`/`teacherDisplayName` 네 파라미터의 설명 시작 컬럼을 수동으로 맞췄다(`OutingService.java:73-75` 등에서 이미 쓰는 방식과 동일한 스타일). 그런데 정렬 폭이 `teacherDisplayName`(18자) 기준으로 잡혀 있어, 더 긴 `applicantDisplayName`(20자)의 설명("대표 신청자의...")과 이어지는 줄("신청이 없으면 `{@code null}`")만 나머지 네 줄보다 한 칸 오른쪽에서 시작한다. checkstyle은 공백 정렬을 검사하지 않아 빌드는 통과하지만, 이 저장소가 다른 파일에서 일관되게 지키는 수동 정렬 관례에서 벗어난다.

**해결 방안**:
1. 다섯 파라미터의 설명 시작 컬럼을 `applicantDisplayName` 기준으로 다시 맞춘다(나머지 네 줄의 공백을 한 칸씩 늘림). 기존 수동 정렬 관례를 유지하면서 가장 빠르게 고칠 수 있다.
2. 컬럼 정렬을 포기하고 `@param 이름 설명` 사이를 공백 한 칸으로 통일한다. 파라미터명이 추가/변경될 때마다 다른 줄까지 다시 맞출 필요가 없어 유지보수 비용은 낮아지지만, `OutingService.java` 등 기존 파일과 스타일이 달라지므로 저장소 전체 차원의 일관성 판단이 별도로 필요하다.

## Critical 없음

보안, 데이터 유실/손상, 서비스 전체 장애로 이어지는 항목은 발견하지 못했다. `registerCampDates`의 요일/중복 검증 로직(`DayOfWeek.FRIDAY`/`SATURDAY`/`SUNDAY` 사용 확인 포함), `SchoolCampSession` 엔티티와 `V11__add_schoolcamp_session.sql`의 컬럼/널러블 대응, `getCalendar`의 `OPEN`/`CLOSED` 계산과 두 이름 필드가 항상 `null`인지는 코드/마이그레이션을 직접 대조해 확인했고, 실패 시 부분 저장이 남지 않는 것도 `@Transactional` 적용과 `IDENTITY` 전략에서의 롤백 동작으로 확인했다(3번 항목에서 서술한 자기중복 케이스도 데이터 정합성 자체는 안전함).
