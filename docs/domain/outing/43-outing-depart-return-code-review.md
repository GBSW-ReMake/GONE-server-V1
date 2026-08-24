# #43 외출증 출발/도착 보고 API — 코드 리뷰 결과

리뷰 대상: `feat/#43-outing-depart-return` 브랜치, `git diff dev...HEAD` 전체(20개 파일,
+1287/-28). 기준 문서: [43-outing-depart-return.md](./43-outing-depart-return.md)(승인된
기획서). `code-review` 스킬 가이드에 따라 (1) 기획서 대비 범위 일치 여부, (2) 기존 컨벤션
준수, (3) 로직/경계값/동시성/테스트 적정성 순으로 점검했다. `./gradlew checkstyleMain
checkstyleTest`는 경고 없이 통과했고, `./gradlew test --tests "com.remake.gone.outing.*"
--rerun`도 전부 성공했다(빌드/스타일 게이트는 이 문서의 지적 대상이 아니다).

## 요약
- Critical: 없음
- High: 없음
- Medium: 2건 (#1, #2) — 모두 반영 완료
- Low: 1건 (#3) — 반영 완료

**보스 결정 및 반영 내역(2026-08-24):**
1. IDOR: `OutingDepartReturnOwnershipIntegrationTest` 추가(실제 학생 2명 + 외출증을 DB에
   저장하고 `@SpringBootTest`로 403/`OUTING_007`을 검증).
2. 낙관적 락 충돌: `departOuting`/`returnOuting`에만 개별로
   `ObjectOptimisticLockingFailureException` → `OutingErrorCode.ALREADY_PROCESSED`(409)
   변환 추가(`OutingService.saveOrRejectAsAlreadyProcessed`). `approveOuting`/`rejectOuting`은
   범위 밖으로 남겨두고, 두 API 계열의 동시성 처리가 갈리는 점은 후속 이슈로 분리한다.
3. 좌표 범위: `OutingLocationRequest`에 `@DecimalMin`/`@DecimalMax` 추가(위도 -90~90,
   경도 -180~180).

기획서 범위 이탈(스코프 크립)이나 누락된 필수 동작은 발견되지 않았다. 검증 순서(404 →
403 → 운영시간 400 → 상태 409 → 반경 400), 에러 코드 재사용(`OUTING_007`/`OUTING_005`),
경계값(운영시간 08:40/20:30, 자체 시간대 startTime/endTime) 처리, `DINNER` 종료 시각
버그 수정, `OutingQueryStatus` 확장 모두 기획서와 정확히 일치한다.

---

## 1. 🟡 Medium — 소유권 기반 403(IDOR 방지)이 end-to-end 경로로 검증되지 않음

**문제**: `OutingDepartAuthorizationTest`/`OutingReturnAuthorizationTest`
(`src/test/java/com/remake/gone/outing/controller/OutingDepartAuthorizationTest.java`,
`OutingReturnAuthorizationTest.java`)는 `@SpringBootTest` + 실제 필터 체인으로
`@PreAuthorize("hasRole('STUDENT')")`가 TEACHER 역할을 막는지(401/403)만 검증한다.
그런데 본인 외출증이 아닌 경우의 403(`OutingService.validateOwnership` →
`OutingErrorCode.ACCESS_DENIED`, `OutingService.java:290-294`)은
`OutingServiceTest.DepartOuting.rejectsWhenNotOwner`/`ReturnOuting.rejectsWhenNotOwner`
(순수 Mockito 단위 테스트, 실제 DB/필터 체인/JSON 직렬화를 거치지 않음)로만 검증된다.
즉 "STUDENT 역할이지만 다른 학생의 외출증 code로 출발/도착을 시도하는" 실제 공격
시나리오(IDOR)가 실 서버 경로로 한 번도 실행되지 않는다. 이 정확히 같은 유형의 문제가
`docs/rules/code-review-template.md`의 예시 항목으로 이미 등재돼 있을 만큼
(`GetOutingDetail.rejectsUnrelatedUser`) 이 코드베이스에서 반복되는 패턴이다.

**해결 방안**:
1. 실제 DB에 학생 2명 + 외출증 픽스처를 만들어 `@SpringBootTest`로 "학생 A 소유의
   `code`를 학생 B 토큰으로 출발 보고 시도 → 403 `OUTING_007`"을 검증하는 통합 테스트를
   추가한다. 가장 확실하지만, 이 프로젝트에는 아직 소유권 레벨까지 실제 DB로 검증하는
   픽스처 기반 통합 테스트 선례가 없어(기존 `OutingDetailAuthorizationTest`도 역할
   체크만 함) 새 테스트 패턴/픽스처 헬퍼를 도입하는 비용이 든다.
2. QA(10단계)에서 실서버로 수동 재현하고 그 결과를 QA 문서에 남기는 것으로 대체한다.
   비용은 낮지만, 이후 `validateOwnership` 호출 순서가 바뀌거나 실수로 제거돼도 CI가
   잡아주지 못한다.

---

## 2. 🟡 Medium — 출발/도착 낙관적 락 충돌이 500으로 노출됨(연속 탭 시나리오)

**문제**: `departOuting`/`returnOuting`(`OutingService.java:237-288`)은 "조회 →
검증 → 필드 변경 → `save`" 순서로 동작하며, 이 사이 동시성 제어는 `Outing.version`
(`@Version`, `Outing.java:123-125`)에 의한 낙관적 락뿐이다. 학생이 네트워크 지연 중
"출발" 버튼을 두 번 누르거나(모바일에서 흔한 더블 탭/재시도), 클라이언트가 타임아웃 후
동일 요청을 재전송하면 두 스레드가 같은 `Outing`을 같은 `version`으로 읽고, 둘 다
`status == APPROVED` 검증을 통과한 뒤 하나만 커밋에 성공한다. 나중에 커밋을 시도하는
쪽은 `ObjectOptimisticLockingFailureException`을 던지는데, `departOuting`/
`returnOuting` 어디에도 이를 잡는 코드가 없고 `GlobalExceptionHandler`
(`src/main/java/com/remake/gone/common/exception/GlobalExceptionHandler.java`)에도
전용 핸들러가 없어(`CustomException`/`MethodArgumentNotValidException`/
`DataIntegrityViolationException` 등만 있고 낙관적 락 예외 핸들러는 없음) 최종적으로
259번째 줄의 범용 `Exception` 핸들러가 받아 `500`으로 응답한다. 학생 입장에서는
"이미 처리된 요청"(`409 OUTING_005`)이 아니라 원인 불명의 서버 오류로 보인다.
(`markSingleOutingAsMissed`는 같은 예외를 명시적으로 잡아 경고 로그만 남기는데(
`OutingService.java:404-409`), 유독 이 두 신규 메서드만 그 패턴을 따르지 않는다.)
다만 이 TOCTOU 구조 자체는 `approveOuting`/`rejectOuting`(#30/#31)에도 동일하게
있던 기존 패턴이라, 이번 PR이 새로 만든 결함은 아니고 같은 패턴을 그대로 확장한 것이다
— 다만 승인/거절은 선생님이 단발성으로 누르는 반면 출발/도착은 학생이 이동 중 불안정한
네트워크에서 누르는 셀프서비스 버튼이라 실제로 트리거될 가능성이 상대적으로 더 높다.

**해결 방안**:
1. `departOuting`/`returnOuting`에서 `ObjectOptimisticLockingFailureException`을 잡아
   `OutingErrorCode.ALREADY_PROCESSED`(409)로 변환한다 — 사용자에게는 "이미 처리된
   요청"이라는 의미 있는 응답을 주고, 원인도 실제로 그것이 맞다. 비용은 낮지만(메서드당
   try/catch 한 줄), `approveOuting`/`rejectOuting`은 그대로 남겨두면 두 API 계열이
   동시성 실패를 다르게 다루는 비일관성이 생긴다 — 함께 고치거나, 최소한 후속 이슈로
   남긴다는 점을 이슈/PR에 명시해야 한다.
2. `GlobalExceptionHandler`에
   `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)`를 전역으로
   추가해 모든 도메인에 걸쳐 409로 변환한다 — 도메인별 개별 처리보다 일관적이고
   `markSingleOutingAsMissed`의 개별 catch와도 중복이 줄지만, 도메인마다 "충돌 시
   어떤 에러 코드/메시지가 적절한지"가 다를 수 있어(예: 출발/도착은 `OUTING_005`가
   자연스럽지만 다른 도메인은 다른 코드가 필요할 수 있음) 범용 핸들러가 잘못된 메시지를
   낼 위험이 있다.
3. 아무 조치도 하지 않고 QA에서 실제 더블 탭 재현 빈도를 관찰한 뒤 후속 이슈로 미룬다 —
   비용은 0이지만, 이미 알려진 결함을 알면서 배포하는 것이므로 이슈에 명시적으로
   남겨야 한다(기존 패턴 확장이라는 근거만으로 방치하면 안 된다).

---

## 3. 🟢 Low — `OutingLocationRequest`에 위도/경도 범위 검증이 없음

**문제**: `OutingLocationRequest`(`src/main/java/com/remake/gone/outing/dto/
OutingLocationRequest.java:11-14`)는 `latitude`/`longitude`에 `@NotNull`만 걸려 있고
값의 범위(위도 -90~90, 경도 -180~180)는 검증하지 않는다. 예를 들어 `latitude: 999.0`
같은 값도 컨트롤러의 `@Valid`를 그대로 통과해 `GeoUtils.distanceMeters`로 넘어간다.
실질적인 피해는 없다 — 하버사인 계산이 예외 없이 끝나고, 그 결과 거리는 사실상 항상
`schoolRadiusMeters`를 넘어 `400 OUTING_009`로 자연스럽게 거부되므로 별도 방어 로직
없이도 결과적으로 안전하다. 다만 이 경우 클라이언트가 받는 에러가 "반경 밖"(OUTING_009)
이라 실제 원인(좌표 값 자체가 잘못됨)과 다른 메시지를 보게 된다.

**해결 방안**:
1. `@DecimalMin("-90")/@DecimalMax("90")`(latitude), `@DecimalMin("-180")/
   @DecimalMax("180")`(longitude)를 추가해 요청 단계에서 `400`(Bean Validation
   기본 메시지)으로 걸러낸다. 구현 비용은 낮지만, 에러 메시지가 `OUTING_XXX` 도메인
   코드 체계 대신 Spring 기본 검증 메시지로 나가 이 프로젝트의 "모든 에러는
   `ErrorCode` 열거형을 통해 나간다" 컨벤션과 형식이 어긋난다(다른 필드 검증들도 이미
   같은 방식을 쓰고 있는지 확인 필요).
2. 그대로 둔다 — 어차피 `OUTING_009`로 거부되어 기능적으로 안전하고, 좌표를 999로
   보내는 클라이언트는 애초에 정상 사용자가 아니므로(GPS가 그런 값을 주지 않음) 에러
   메시지의 정확성보다 우선순위가 낮다고 판단할 수 있다. 이 경우 "왜 범위 검증을
   생략했는지"를 기획서/PR에 남겨 다음 리뷰어가 같은 고민을 반복하지 않게 한다.

---

## Medium/High/Critical 관련 확인 사항 (문제 없음으로 판단한 항목)
- **검증 순서**: `departOuting`/`returnOuting` 모두 기획서 순서(404 → 403 소유권 →
  400 운영시간 → 409 상태 → 400 반경)를 정확히 따른다(`OutingService.java:238-257`,
  `268-288`).
- **경계값**: 운영시간(08:40/20:30)과 자체 시간대(startTime/endTime) 모두 inclusive로
  구현됐고(`isBefore`/`isAfter`만 사용해 경계 시각은 허용), 각 경계값이
  `OutingServiceTest`에 08:39/08:40/20:30/20:31로 명시적으로 테스트된다.
- **`DINNER` 버그 수정**: `OutingTimeSlot.DINNER` 종료 시각이 21:10 → 19:10으로
  수정됐고, 회귀 방지 테스트(`OutingTimeSlotTest.dinnerEndTimeIsNineteenTen`)가
  추가됐다. 전환기 데이터 문제(옛 21:10 규칙으로 신청된 PENDING/APPROVED 건)는
  기획서에서 이미 "해당 없음"으로 확인됐고 이번 리뷰에서 별도 마이그레이션이 필요하다고
  판단되는 근거도 찾지 못했다.
- **`OutingQueryStatus` 확장**: `DEPARTED`/`RETURNED` 추가와 기존
  `doesNotIncludeUnreachableStatuses()` 테스트 삭제/`convertsToOutingStatusWithSameName()`
  보강이 기획서 지시대로 반영됐다. `toOutingStatus()`가 이름 기반 변환이라 `OutingStatus`
  쪽에 이미 존재하는 값과 정확히 일치하는지도 확인했다(일치함).
- **에러 코드 재사용**: `OUTING_007`(`ACCESS_DENIED`)을 새로 만들지 않고 재사용,
  `OUTING_009`/`OUTING_010` 신규 추가 — 기획서와 일치, 기존 번호(`001~008`,
  `011~015`)와 충돌 없음.
- **응답 DTO 하위 호환성**: `OutingResponse`에 `departedAt`/`returnedAt`/`offSchedule`
  필드만 추가, 기존 필드 순서/의미 변경 없음. 컨트롤러 테스트들의 기존
  `OutingResponse` 생성자 호출부도 전부 새 필드 3개를 반영해 갱신됐다(빠짐없이 확인함).
- **동일 트랜잭션 내 지연 로딩**: `outing.getStudent()`/`outing.getTeacher()` 접근이
  `@Transactional` 메서드 안에서 이뤄져 `LazyInitializationException` 위험 없음.
- **빌드/스타일 게이트**: `checkstyleMain`/`checkstyleTest` 경고 없음,
  `com.remake.gone.outing.*` 테스트 전체 통과(재실행으로 캐시 아님을 확인).

## 확인했지만 이번 리뷰의 지적 대상으로 보지 않은 사항
- **날짜가 다른 날 출발 보고 허용**(예: 3일 뒤로 승인된 외출증을 오늘 출발 보고) —
  `validateOperatingHours`는 시각만 보고 날짜는 보지 않는다. 다만 기획서가 "시간대
  밖 출발/도착 보고는 차단하지 않고 허용, `offSchedule`로만 안내"라고 명시적으로
  확정했고 대안(선생님 승인 단계, 위치 핑 기반 자동 감지)도 명시적으로 기각했으므로
  이 동작은 버그가 아니라 승인된 설계다.
- **낙관적 락 예외를 제외한 동시성 처리** — `applyOuting`의 학생 행 배타적 락 등 기존
  패턴과 충돌하는 부분 없음.
