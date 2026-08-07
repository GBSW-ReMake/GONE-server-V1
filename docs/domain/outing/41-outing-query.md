# #41 외출증 본인/담당/단건 조회 API — 기획서

관련 이슈: [#41 외출증 본인/단건 조회 API 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/41)
마스터 기획서: [1_outing-domain.md](./1_outing-domain.md)의 "6. `GET /api/v1/outings/me`",
"7. `GET /api/v1/outings/{code}`"

## 개요/목적
신청(#29)/승인(#30)/거절(#31)까지는 상태를 "바꾸는" API만 있고, 조회할 방법이 없다. 이번
이슈는 아래 3개 엔드포인트를 다룬다:
1. 학생 본인이 신청한 외출증 조회 — 학생 앱의 "출발/도착 버튼이 있는 그 화면"의 기반 데이터
2. **선생님에게 배정된 외출증 조회(신규 추가)** — 선생님이 "나한테 들어온 요청/내가
   승인·거절한 기록"을 한 번에 보는 화면. 마스터 기획서 작성 당시엔 이 화면이 별도로 없었고
   (8/11번은 학교 전체 현황이지 본인에게 배정된 것만 거르는 화면이 아니다), 실사용 관점에서
   빠진 부분이라 이번 기획에서 새로 추가한다.
3. 단건 상세 조회 — 알림/딥링크 등으로 특정 외출증 하나를 열어보는 화면의 기반 데이터

"가시성" 화면(실시간 목록 `/active`, 학교 전체 현황 `/outings?date&status`, 8/11번)은 대상
사용자(선도부/관리자 등 학교 전체를 보는 화면)와 목적이 달라 별도 이슈로 분리한다.

## 조회 기간 파라미터 설계 (1/2번 공통)
1/2번 엔드포인트는 조회 기간을 아래처럼 표현한다.

- **`period`**(선택, enum: `TODAY` / `THIS_WEEK` / `THIS_MONTH` / `CUSTOM`, 생략 시 기본값
  `THIS_WEEK`) — `TODAY`/`THIS_WEEK`/`THIS_MONTH`는 서버가 내부적으로 실제 날짜 범위를
  계산한다(경계 계산 로직이 프론트마다 중복/어긋나는 걸 막기 위함 — 아래 "왜 이렇게
  설계했나" 참고).
- **`dateFrom`/`dateTo`**(선택, `yyyyMMdd`) — `period=CUSTOM`일 때만 사용한다. 둘 다 필수이며,
  `MealController`/`TimetableController`와 동일한 `@DateTimeFormat(pattern = "yyyyMMdd")`
  패턴을 재사용한다.

**검증 규칙**(엄격 모드 — 모순된 조합은 조용히 무시하지 않고 에러로 알린다):
- `period == CUSTOM`인데 `dateFrom`/`dateTo` 중 하나라도 없음 → `400` `OUTING_014`
- `period != CUSTOM`인데 `dateFrom`/`dateTo`가 하나라도 같이 옴 → `400` `OUTING_014`
  (모순된 요청 — "이번 주만 보고 싶다면서 날짜도 같이 보냄"은 클라이언트 버그일 가능성이 높아
  조용히 무시하지 않고 명시적으로 알려준다)
- `period == CUSTOM`이고 `dateFrom > dateTo` → `400` `OUTING_013`
- `period` 값 자체가 저 4개 중 하나가 아님 → `400`(Spring enum 바인딩 실패, 기존
  `GlobalExceptionHandler` 공통 처리 — `yyyyMMdd` 형식 오류와 동일한 메커니즘, 신규 코드
  불필요)

**서버 내부 계산 규칙** (KST 기준):
- `TODAY`: `dateFrom = dateTo = today`
- `THIS_WEEK`: 이번 주 월요일 ~ 일요일(`today.with(DayOfWeek.MONDAY)` ~
  `today.with(DayOfWeek.SUNDAY)`)
- `THIS_MONTH`: 이번 달 1일 ~ 말일(`YearMonth.now(KST).atDay(1)` ~ `.atEndOfMonth()`)
- `CUSTOM`: 요청받은 `dateFrom`/`dateTo` 그대로 사용

**신규 유틸리티**: `outing/enums/OutingQueryPeriod`(단순 enum, 4개 값 — 이 프로젝트에 이미
있는 `outing/utils/OutingTimeUtils.java`(순수 함수, `final class` + private 생성자 +
`static` 메서드) 패턴을 그대로 따르는 `outing/utils/OutingQueryPeriodResolver`에 위 계산
로직을 넣는다. `CUSTOM`은 외부 입력(`dateFrom`/`dateTo`)에 의존해 `OutingTimeSlot`처럼
enum 스스로 값을 들고 있을 수 없으므로(그 셋은 서버가 정한 고정값이라 enum에 넣을 수 있지만
`CUSTOM`은 호출마다 다름), enum은 값만 갖고 실제 계산은 별도 정적 유틸 함수로 분리한다.
```java
public record OutingDateRange(LocalDate from, LocalDate to) {}

public final class OutingQueryPeriodResolver {
  public static OutingDateRange resolve(
      OutingQueryPeriod period, LocalDate today, LocalDate dateFrom, LocalDate dateTo) {
    // period별 계산/검증 로직 (본문 "서버 내부 계산 규칙" 참고)
  }
}
```

> **왜 이렇게 설계했나 (기존: `dateFrom`/`dateTo`만 받고 기본값만 이번 주)**: 처음엔
> `dateFrom`/`dateTo`만 받고 둘 다 생략 시 이번 주로 기본값을 두는 안이었는데, "오늘"/"이번
> 달"처럼 다른 프리셋을 보려면 그 경계(이번 주가 월요일 시작인지, 이번 달 말일 계산 등)를
> **클라이언트가 직접 계산**해서 `dateFrom`/`dateTo`로 보내야 했다. 프론트가 앱/웹 등
> 여러 개로 늘어나면 이 계산이 중복되고 어긋날 위험이 있어(예: 한쪽만 일요일 시작으로
> 잘못 계산), 서버가 흡수하는 쪽(`period` enum)으로 바꿨다. 이 프로젝트에 이미 있는
> `OutingTimeSlot`(`LUNCH`/`DINNER`는 서버 고정값, `CUSTOM`만 클라이언트 입력)과 정확히
> 같은 패턴이라 새 개념을 만드는 것도 아니다.

## 상태(status) 필터 & `MISSED` 판정 (1/2번 공통)
1/2번 모두 `status`(선택, enum: `PENDING`/`APPROVED`/`REJECTED`/`MISSED`) 쿼리 파라미터로
결과를 걸러볼 수 있다. 생략하면 기존과 동일하게 전부 반환한다(필터는 선택 사항이지 필수가
아니다).

`DEPARTED`/`RETURNED`는 필터 값에서 뺀다 — 출발/도착 보고 엔드포인트(마스터 기획서 4/5번)가
아직 없어서 어떤 외출증도 그 상태에 절대 도달할 수 없다. 지금 필터 목록에 넣어봐야 항상
빈 결과만 나오는 죽은 옵션이라 뺀다(그 엔드포인트들이 실제로 생기는 이슈에서 다시 추가).
응답의 `status` 필드 자체는 `OutingStatus`(엔티티에 이미 정의된 전체 enum) 타입 그대로
유지해 나중에 그 값들이 실제로 나타날 때 스키마 변경 없이 자연스럽게 확장된다.

**`MISSED`란**: 담당 선생님이 승인도 거절도 하지 않은 채 그 외출증의 시작 시각이 지나버린
`PENDING` 건 — "누락됨"을 뜻한다. DB의 `status` 컬럼은 계속 `PENDING`으로 남아있다(이번
이슈에서 DB를 직접 바꾸지 않는다 — 아래 "왜 DB는 그대로 두는가" 참고). 대신 **응답을
만드는 시점에 실시간으로 계산**해서, 실제로는 `PENDING`이지만 마감이 지났으면 응답의
`status` 필드에 `PENDING` 대신 `MISSED`를 넣는다. `status=PENDING` 필터는 "아직 마감
전인 PENDING"만, `status=MISSED` 필터는 "마감 지난 PENDING"만 걸러준다 — 같은 DB 값
(`PENDING`)이 실시간 계산으로 둘 중 하나로 갈린다.

**계산 규칙** (신청 시 이미 쓰는 마감 판정과 동일한 개념 — `OutingService.validateDeadline`
참고): `outing.getStatus() == PENDING`이고, `(outingDate가 오늘보다 과거)` 또는
`(outingDate == 오늘이고 지금 시각 >= startTime)`이면 `MISSED`로 판정한다.

> **왜 DB는 그대로 두는가(스케줄러로 실제 반영은 후속 이슈)**: DB에도 실제로 `MISSED`를
> 반영하는 게(주기적으로 도는 `@Scheduled` 작업으로) 맞는 방향이지만, 그건 이 이슈(조회
> API)의 범위를 넘는 새 인프라(첫 백그라운드 스케줄러) 도입이라 별도 이슈로 분리한다(아래
> "리스크 및 고려사항" 참고). 다만 **정확성에는 문제가 없다** — "지금이 마감을 지났는가"는
> 계산 비용이 사실상 0이라 매번 다시 계산해도 되고, 그래서 이 API가 보여주는 값은 스케줄러가
> 아직 안 돌았어도 항상 실시간으로 정확하다. 스케줄러는 오직 DB를 직접 보는 사람(SQL, 향후
> 관리자 화면 등)을 위한 뒷정리일 뿐이라 몇 분 지연돼도 이 API의 정확성에는 영향이 없다.

## 엔드포인트

### 1. `GET /api/v1/outings/me/requests` — 본인이 신청한 외출증 조회
**권한**: `STUDENT` (`@PreAuthorize("hasRole('STUDENT')")`)

> 원래 `GET /outings/me`로 기획했으나, 2번(선생님용) 엔드포인트를 추가하면서 "me"만으로는
> "내가 신청한 것"인지 "나한테 배정된 것"인지 모호해졌다. 두 엔드포인트를 하나로 합쳐
> 역할별로 응답을 분기하는 대안도 검토했지만, 요청자 역할을 판단하는 데 서버 왕복이 드는 게
> 아니라(Access Token의 `roles` 클레임을 클라이언트가 로그인 시점에 이미 디코드해 들고
> 있음 — 추가 `/auth/me` 호출 불필요) 엔드포인트를 합쳐서 얻는 이득이 없었다. 오히려 "학생이
> 신청한 것 조회"와 "선생님에게 배정된 것 조회"는 조회 주체(`studentId` vs `teacherId`)가
> 다른 별개의 쿼리라 원칙 1(한 가지를 잘하기)에 따라 분리하고, 대신 이름을
> `/me/requests`(신청한 것) / `/me/received`(배정된 것)로 명확히 구분했다.

> 마스터 기획서 작성 시점에는 `@EnableMethodSecurity` 인프라가 없어 "서비스에서 직접 역할
> 검사"로 적었지만, #30에서 그 인프라가 도입됐으므로 승인/거절과 동일하게 선언적
> `@PreAuthorize`를 쓴다. 역할이 아니면 기존 인프라 그대로 `403` `COMMON_003`(FORBIDDEN)이
> 반환된다 — 신규 에러 코드 불필요.

**요청**: 쿼리 파라미터 `period`/`dateFrom`/`dateTo`(위 "조회 기간 파라미터 설계" 절 그대로)
+ `status`(선택 — 위 "상태 필터 & `MISSED` 판정" 절 그대로).

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": [
    {
      "code": "string",
      "studentNickname": "string",
      "studentProfileImageUrl": "string | null",
      "studentRealName": "string",
      "studentGrade": "number",
      "studentClassNo": "number",
      "teacherName": "string",
      "reason": "string",
      "outingDate": "string (yyyyMMdd)",
      "timeSlot": "LUNCH | DINNER | CUSTOM",
      "startTime": "string (HH:mm)",
      "endTime": "string (HH:mm)",
      "status": "PENDING | APPROVED | REJECTED | DEPARTED | RETURNED | MISSED",
      "rejectedReason": "string | null"
    }
  ],
  "message": "외출증 목록을 조회했습니다.",
  "code": null
}
```
그 범위(+필터)에 해당하는 게 없으면 **빈 배열(`[]`)**, `200 OK`(빈 컬렉션은 에러가 아니다 —
`null`을 반환하지 않는다. 이유는 아래 "리스크 및 고려사항" 참고).

**구현 로직**
1. `@AuthenticationPrincipal`에서 `studentUserId` 추출(역할은 `@PreAuthorize`가 이미 확인)
2. `OutingQueryPeriodResolver.resolve(period, today, dateFrom, dateTo)`로 실제
   `[dateFrom, dateTo]` 확정(검증 실패 시 위 규칙대로 `OUTING_013`/`OUTING_014`)
3. `outingRepository.findByStudentIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(
   studentUserId, dateFrom, dateTo)`로 조회(신규 리포지토리 메서드) — DB 레벨에서
   `status`로 거르지 않는다(데이터가 적어 굳이 쿼리를 복잡하게 만들 이유가 없다 — 아래
   4번 참고)
4. 각 `Outing`을 `toResponse(...)`로 변환하면서 **유효 상태**(effective status)를 계산:
   `outing.getStatus() == PENDING`이고 마감이 지났으면(`OutingTimeUtils`의 새 판정 함수,
   "상태 필터 & MISSED 판정" 절 참고) 응답의 `status`를 `MISSED`로, 아니면 저장된 값 그대로
   사용
5. `status` 쿼리 파라미터가 있으면 유효 상태 기준으로 필터링 후 반환(없으면 전부 반환)

**에러**
- 인증 안 됨 → `401` `COMMON_002`(기존 인프라)
- `STUDENT` 역할이 아님 → `403` `COMMON_003`(기존 인프라)
- `dateFrom`/`dateTo` 형식이 `yyyyMMdd`가 아니거나 `period`/`status` 값이 정해진 enum 중
  하나가 아님 →
  `400`(기존 공통 핸들러)
- `period`/`dateFrom`/`dateTo` 조합이 모순됨 → `400` `OUTING_014`(신규)
- `dateFrom`이 `dateTo`보다 늦음(`CUSTOM`일 때) → `400` `OUTING_013`(신규)

---

### 2. `GET /api/v1/outings/me/received` — 나에게 배정된 외출증 조회 (신규)
**권한**: `TEACHER` (`@PreAuthorize("hasRole('TEACHER')")`)

담당 선생님으로 지정된 외출증을 조회한다 — "지금 들어온 요청(`PENDING`)", "내가 승인한
기록(`APPROVED`)", "내가 거절한 기록과 사유(`REJECTED`, `rejectedReason`)", "응답 없이
마감이 지나버린 요청(`MISSED`)"을 `status` 필터로 걸러볼 수 있다(필터 생략 시 전부 반환,
1번과 동일한 방식).

**요청**: 1번과 완전히 동일한 `period`/`dateFrom`/`dateTo`/`status` 규칙.

**응답** (`200 OK`) — 1번과 완전히 동일한 `OutingResponse` 리스트 구조(`status`에 `MISSED`
포함). `teacherName`은 항상 호출한 본인의 이름이라 다소 중복이지만, 스키마를 1번과 통일해
프론트가 같은 컴포넌트로 두 화면을 렌더링할 수 있게 하는 게 더 낫다고 판단했다(원칙 3, 6 —
새 DTO를 만들지 않아 하위 호환/일관성 모두 좋음).

**구현 로직**
1. `@AuthenticationPrincipal`에서 `teacherUserId` 추출(역할은 `@PreAuthorize`가 이미 확인)
2. `OutingQueryPeriodResolver.resolve(...)`로 범위 확정(1번과 동일 로직/검증 공유)
3. `outingRepository.findByTeacherIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(
   teacherUserId, dateFrom, dateTo)`로 조회(신규 리포지토리 메서드) — 1번과 동일하게 DB
   레벨 `status` 필터 없이 넓게 가져온다(**중요**: DB에 `MISSED`가 실제로 저장되는 일은
   없으므로 — #42 스케줄러 전까지 — 만약 여기서 `WHERE status = 'MISSED'`처럼 걸렀다면
   영원히 빈 결과만 나오는 버그가 됐을 것. 반드시 넓게 가져온 뒤 유효 상태를 계산해서
   걸러야 한다)
4. 각 `Outing`의 유효 상태 계산 + `toResponse(...)` 변환은 1번과 동일 로직 공유
5. `status` 쿼리 파라미터가 있으면 유효 상태 기준으로 필터링 후 반환

**에러**: 1번과 동일한 체계(`401`/`403`/`400`/`OUTING_013`/`OUTING_014`), `TEACHER` 역할
여부만 다르다.

---

### 3. `GET /api/v1/outings/{code}` — 단건 상세 조회
**권한**: `@PreAuthorize("isAuthenticated()")` + 서비스에서 소유권/역할 확인 — 그 외출증의
신청 학생 본인, 지정된 담당 선생님, 또는 `DISCIPLINE`/`ADMIN` 역할 보유자

마스터 기획서가 이미 이 조합을 "역할 OR 소유권"으로 명시하고, `@PreAuthorize`의 SpEL로
욱여넣기보다 서비스 메서드 안에서 `if`로 명시적으로 검사하는 걸 이 프로젝트의 권한 모델
원칙으로 못박아뒀다 — 그 부분(4개 조건을 SpEL 한 줄에 몰아넣지 않는다)은 그대로 따른다.

> **단, 마스터 기획서의 판단을 검토 없이 그대로 가져오지는 않는다**(리뷰 중 지적됨,
> [api-design.md](../../rules/api-design.md) "마스터 기획서 재검토" 항목 참고). 원안은
> 컨트롤러에 `@PreAuthorize` 없이 `SecurityConfig`의 전역 `authenticated()`에만 기대는
> 것이었는데, 그러면 같은 `OutingController` 안에서 `approveOuting`/`rejectOuting`엔
> `@PreAuthorize`가 있고 이 메서드만 없어 **시각적 일관성이 깨진다** — 나중에 리뷰어가
> "인가 애노테이션이 빠졌다"고 매번 의심하거나, 누군가 실수로 잘못된 단일 역할 제한을
> 덧붙일 위험이 있다. 그래서 컨트롤러에 최소`@PreAuthorize("isAuthenticated()")`를
> 붙인다 — `SecurityConfig`와 중복이지만 (1) 같은 파일 안 다른 메서드와 패턴이 통일되고
> (2) `SecurityConfig`가 나중에 실수로 바뀌어도 이 메서드 단독 방어선이 하나 더 생긴다
> (defense in depth). 소유권/세부 역할(`DISCIPLINE`/`ADMIN`) 판단은 여전히 서비스 코드가
> 담당한다.

**요청**: 경로 변수 `code`만 사용, 바디 없음

**응답** (`200 OK`) — `OutingResponse` 단건(위 1/2번과 같은 필드 구조, `status`에 `MISSED`
포함 — 단건도 목록과 동일하게 실시간 유효 상태를 보여준다. 승인/거절 안 된 채 마감 지난
건을 열어봤는데 여전히 `PENDING`으로 보이면 오히려 일관성이 깨진다)

**구현 로직**
1. `code`로 `Outing` 조회(`findByCode`), 없으면 `404`
2. `callerUserId == outing.getStudent().getId()` 이거나
   `callerUserId == outing.getTeacher().getId()` 이거나,
   `userRoleRepository.findRoleCodesByUserId(callerUserId)`에 `DISCIPLINE` 또는 `ADMIN`이
   있으면 통과, 아니면 `403`
3. 유효 상태 계산 포함한 `toResponse(...)`로 변환해 반환(1/2번과 동일 로직 공유)

**에러**
- 위 권한 조건에 해당 안 되는 사용자가 조회 시도 → `403` `OUTING_007`(신규 — 마스터
  기획서 에러 코드 표에 이미 예약돼 있던 번호를 이번에 처음 채운다. IDOR 방지)
- 존재하지 않는 `code` → `404` `OUTING_006`(기존 코드 재사용)
- 인증 안 됨 → `401` `COMMON_002`(기존 인프라)

## 데이터 모델 변경
없음(DB 스키마 변경 없음). `OutingStatus`에 `MISSED` 값을 추가하지만 문자열로 저장되는
enum이라 Flyway 마이그레이션은 불필요하다 — 다만 **이 이슈에서 `MISSED`가 실제로 DB에
저장되는 일은 없다**(항상 응답 변환 시점에만 계산돼 끼워진다). `OutingRepository`에는 조회
전용 메서드만 추가한다(`findByStudentIdAndOutingDateBetween...`,
`findByTeacherIdAndOutingDateBetween...`, Spring Data 쿼리 메서드로 충분, `@Query` 불필요 —
기존 `findByStudentIdAndOutingDateAndStatusIn`과 같은 패턴).

## 영향 받는 기존 코드
- `outing/enums/OutingQueryPeriod`(신규): `TODAY`/`THIS_WEEK`/`THIS_MONTH`/`CUSTOM` 4개 값만
  갖는 단순 enum
- `outing/utils/OutingQueryPeriodResolver`(신규): 위 계산/검증 로직을 담는 정적 유틸리티
  (`OutingTimeUtils`와 같은 패턴), `OutingDateRange(LocalDate from, LocalDate to)` record 반환
- `outing/enums/OutingStatus`: `MISSED` 값 추가(순수 확장, 기존 값/`ACTIVE_STATUSES`
  구성에 영향 없음 — `ACTIVE_STATUSES`는 저장된 값 기준이고 `MISSED`는 이 이슈에서 저장되지
  않으므로 그대로 둔다)
- `outing/utils/OutingTimeUtils`: 마감 판정 순수 함수 추가, 예:
  `isPastDeadline(LocalDate outingDate, LocalTime startTime, LocalDate today, LocalTime now)`
  → `outingDate.isBefore(today) || (outingDate.isEqual(today) && !now.isBefore(startTime))`.
  기존 `OutingService.validateDeadline`(신청 시 마감 검증, `outingDate.isEqual(today)`
  분기만 있음)과 개념은 같지만 "이미 지난 날짜" 분기가 추가로 필요해 별도 함수로 둔다(신청
  검증은 과거 날짜 자체를 `validateDateRange`가 먼저 막아 그 분기가 필요 없었던 것 —
  기존 로직을 건드리지 않고 새 함수만 추가).
- `OutingRepository`:
  - `findByStudentIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(Long, LocalDate, LocalDate)`
  - `findByTeacherIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(Long, LocalDate, LocalDate)`
  - (반환 타입 둘 다 `List<Outing>`, DB 레벨 `status` 필터 없음 — 위 엔드포인트 3번 구현
    로직의 "중요" 설명 참고)
- `OutingService`: `getMyRequests(Long studentUserId, LocalDate dateFrom, LocalDate dateTo,
  OutingStatus statusFilter)`, `getReceivedOutings(Long teacherUserId, LocalDate dateFrom,
  LocalDate dateTo, OutingStatus statusFilter)`, `getOutingDetail(Long callerUserId, String
  code)` 추가. 기존 `private toResponse(Outing, User, User)`는 유효 상태 계산을 위해
  `LocalDate today`/`LocalTime now`를 추가로 받도록 **시그니처만** 바뀐다 — 기존 호출부
  (`applyOuting`/`approveOuting`/`rejectOuting`)는 이미 그 시점의 `today`/`now`를 갖고 있어
  그대로 전달하면 되고, 그 세 메서드가 반환하는 `status` 값은 실제로 절대 안 바뀐다(방금
  생성/승인/거절된 건이 그 즉시 마감을 지나있을 수는 없으므로) — **기존 API 응답의 관찰
  가능한 동작은 변하지 않는, 내부 리팩터링**이다.
- `OutingController`: `GET` 매핑 3개 추가. 3번(`GET /{code}`)에는 `@PreAuthorize("isAuthenticated()")`를
  붙여 같은 컨트롤러의 다른 메서드(`approveOuting`/`rejectOuting`)와 인가 애노테이션 사용
  패턴을 통일한다(위 3번 엔드포인트 설명 참고)
- `OutingErrorCode`: `OUTING_007`(`ACCESS_DENIED` 가칭), `OUTING_013`(`INVALID_DATE_RANGE`
  가칭), `OUTING_014`(`INVALID_PERIOD_PARAMS` 가칭) 추가

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 엔드포인트 3개로 나눔(본인 신청 목록 / 배정 목록 / 단건 상세) —
   조회 주체(`studentId` vs `teacherId`)가 다른 쿼리를 역할 분기로 한 엔드포인트에 욱여넣지
   않는다. "날짜 범위 목록"과 "코드로 단건"도 서로 다른 조회 패턴이라 분리 유지.
2. **빠른 시작**: 요청/응답 예시 위에 명시. `period` 프리셋 덕분에 클라이언트가 날짜 계산
   없이 바로 호출해볼 수 있어 이 원칙에 더 부합하게 됐다.
3. **일관성**: `dateFrom`/`dateTo` 쿼리 파라미터는 `Meal`/`Timetable`이 쓰는 `yyyyMMdd`
   `@DateTimeFormat` 패턴을 그대로 재사용. `period` enum은 이미 있는 `OutingTimeSlot`(프리셋
   + CUSTOM) 패턴을 그대로 재사용. 역할 검사는 #30/#31과 동일하게 `@PreAuthorize`로 통일.
4. **의미 있는 오류**: `OUTING_007`(역할 OR 소유권 실패)은 마스터 기획서가 이미 예약해 둔
   코드. `OUTING_013`(`dateFrom > dateTo`)과 `OUTING_014`(`period`/`dateFrom`/`dateTo` 조합
   모순)는 원인이 서로 달라 분리한다 — 전자는 "범위 값 자체가 앞뒤가 바뀜", 후자는 "애초에
   같이 오면 안 되는 파라미터 조합"이라 다른 실수 유형이다. 모순된 조합을 조용히 무시하지
   않고 에러로 알리기로 한 것도 이 원칙에 따른 결정. `MISSED`를 별도 `status` 값으로 노출한
   것도 같은 맥락 — "승인/거절이 하나도 없었던 것"과 "응답 없이 마감이 지나버린 것"은 사용자
   입장에서 의미가 전혀 다른데 계속 `PENDING`으로만 보여주면 그 차이가 드러나지 않는다.
5. **확장성/성능**: 범위 조회지만 페이지네이션은 넣지 않는다 — 학생/선생님 모두 하루 처리
   가능한 건수가 자연히 작아(`outing-domain.md` "정책 가정" 참고) 아무리 넓은 범위(수년치,
   `CUSTOM`으로 직접 지정 시)를 조회해도 결과 행 수 자체가 작다. 범위 크기 상한도 지금은
   두지 않는다(YAGNI). `GET /{code}`는 단건이라 해당 없음.
6. **하위 호환성**: 세 엔드포인트 모두 신규 추가, 기존 응답/스키마 변경 없음. 1/2번이 같은
   `OutingResponse` 스키마를 공유해 프론트 재사용성도 높다.

## 테스트 방법
로컬 서버(`baseUrl=http://localhost:9091`) 기동 후 Postman 컬렉션(`GONE - Outing API`) +
`GONE - Local Dev` 환경으로 검증한다. 사전 조건은 #31과 동일(`user1`/`teacher1` 계정).

1. 학생 로그인 → 외출증 신청(1건 이상) → `GET /outings/me/requests`(파라미터 없음, 기본값
   `THIS_WEEK`) 호출해 방금 신청한 건이 보이는지 확인
2. 선생님 로그인 → 위에서 신청받은 건에 대해 `GET /outings/me/received` 호출 → `PENDING`
   상태로 보이는지 확인
3. 그 건을 승인/거절 처리 후 다시 `GET /outings/me/received` 호출 → 상태(`APPROVED`/
   `REJECTED`)와 `rejectedReason`(거절 시)이 반영되는지 확인
4. `GET /outings/{code}`를 신청 학생 본인/담당 선생님 토큰으로 각각 호출 → `200`
5. 관계 없는 다른 학생 계정으로 `GET /outings/{code}` 호출 → `403` `OUTING_007`
6. 존재하지 않는 code로 `GET /outings/{code}` → `404` `OUTING_006`
7. `period=TODAY`, `period=THIS_MONTH` 각각 호출 → 기대한 날짜 범위로 조회되는지 확인
8. `period=CUSTOM`에 지난달 1일~말일을 `dateFrom`/`dateTo`로 지정 → 그 기간 전체(거절/완료
   포함) 조회되는지 확인
9. `period=CUSTOM`인데 `dateFrom`/`dateTo` 없이 → `400` `OUTING_014`
10. `period=THIS_WEEK`인데 `dateFrom`도 같이 → `400` `OUTING_014`
11. `period=CUSTOM`, `dateFrom`이 `dateTo`보다 늦게 → `400` `OUTING_013`
12. `period=NOT_A_PERIOD` 등 잘못된 값 → `400`
13. 신청/배정이 없는 기간 조회 → **`data: []`**(`null` 아님) 확인
14. **`MISSED` 시나리오**: 오늘 날짜로 `LUNCH`(12:30 시작) 외출증을 신청한 뒤, 승인/거절
    없이 12:30을 넘겨서 `GET /outings/me/requests`(학생)와 `/me/received`(선생님) 각각
    호출 → 둘 다 `status: "MISSED"`로 보이는지 확인(DB에는 `PENDING`으로 저장돼 있지만
    응답에서 재계산되는지 확인하는 게 핵심)
15. 14번 상태에서 `status=PENDING` 필터로 조회 → 그 건이 **빠지는지**, `status=MISSED`로
    조회 → 그 건만 **나오는지** 확인(DB 값은 그대로 `PENDING`인 채로 필터가 정확히
    갈리는지가 이 기획의 핵심 검증 포인트)
16. `status=REJECTED`/`APPROVED` 등 나머지 값으로도 필터링이 정확한지 확인

## 리스크 및 고려사항
- **빈 결과는 `null`이 아니라 항상 `[]`다.** 이미 `GET /users/search`(#32)가 같은 패턴으로
  검증돼 있고(`docs/domain/user/32-user-search-profile-QA.md`), 프론트 입장에서도 `[]`가
  `data.map(...)`/`data.length` 같은 코드를 null 체크 없이 그대로 쓸 수 있어 더 안전하다.
  `null`을 새로 도입하면 오히려 이 프로젝트 안에서 리스트 응답의 일관성이 깨진다.
- **`DISCIPLINE`/`ADMIN` 테스트 계정이 로컬 DB에 없다** — #31 QA 때도 동일한 제약으로 교사
  소유권 불일치(`OUTING_004`) 케이스를 단위 테스트로 대체 확인했다. 이번에도 두 역할
  경로는 단위 테스트로 커버하고, 수동 e2e는 학생/선생님 본인 케이스 위주로 진행한다.
- **`ADMIN` 관련 고려사항은 이번 범위에서 깊게 다루지 않는다** — 로깅/전체 열람 등 관리
  기능은 나중에 별도 웹 관리자 페이지로 만들 예정이라, 이 API의 `ADMIN` 접근은 프로젝트
  공통 전제("`ADMIN`은 모든 도메인에서 항상 전체 접근 가능", `outing-domain.md` 권한 모델
  참고)를 그대로 상속받는 것 이상으로 설계/검증에 힘을 쏟지 않는다. `DISCIPLINE`(선도부,
  이 API를 실제 앱에서 쓰는 역할)은 계속 단위 테스트로 커버 대상이다.
- `GET /me/requests`, `/me/received` 모두 `status` 필터를 안 주면 범위 안 전체 상태
  (`REJECTED`/`RETURNED`/`MISSED` 등 종료·누락된 것 포함)를 다 보여준다 — 기본값은
  "필터링 없음"이고, 걸러 보고 싶으면 `status`를 명시적으로 지정한다.
- **DB의 `MISSED` 실제 반영(스케줄러)과 승인/거절 마감 차단은 별도 이슈(#42)로 분리했다.**
  이 이슈(#41)는 조회 전용이라 DB를 쓰지 않고, `status=MISSED` 필터/응답 값은 항상 요청
  시점 실시간 계산이라 #42가 아직 진행 전이어도 정확성에 문제가 없다(위 "상태 필터 &
  `MISSED` 판정" 절 참고).
- **선생님이 "여러 반/학년 담당 학생들의 요청을 한 화면에서 보는" 상황을 전제로 설계했다**
  — `teacherId` 하나로 걸러 조회하므로 담임/비담임 구분 없이 "그 선생님이 지정된 모든
  외출증"이 섞여 나온다. 이 프로젝트에는 아직 담임-학급 매핑 개념이 없어(`outing-domain.md`
  "정책 가정" 참고) 애초에 학생이 신청 시 선생님을 자유롭게 지정하는 구조이므로, 이 조회도
  같은 전제를 따르는 게 일관적이다.
- **패키지 구조**: `OutingQueryPeriod`/`OutingQueryPeriodResolver`는 기존 6개 레이어
  목록(`controller/dto/service/exception/entity/repository`)에 없는 `enums`/`utils`
  폴더를 쓰는데, 이 프로젝트에 이미 `outing/enums`(`OutingStatus`, `OutingTimeSlot`),
  `outing/utils`(`OutingTimeUtils`, `OutingCodeGenerator`)가 선례로 있어 새 폴더를 만드는
  게 아니라 기존 폴더에 맞춰 넣는 것이다([code-style.md](../../rules/code-style.md) "컨벤션은
  유지보수성보다 우선하지 않는다" 참고 — 이번 건은 애초에 기존 폴더와 맞아떨어져 별도 검토
  거리조차 아니다).
