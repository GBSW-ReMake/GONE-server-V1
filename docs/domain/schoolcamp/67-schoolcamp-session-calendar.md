# #67 스쿨캠핑 세션 등록 + 캘린더 조회 API — 기획서

관련 이슈: [#67 스쿨캠핑 세션 등록 + 캘린더 조회 API 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/67)
마스터 기획서: [1_schoolcamp-domain.md](./1_schoolcamp-domain.md)의 "도메인 모델 —
`SchoolCampSession`", "엔드포인트 1. `GET /api/v1/school-camps`", "엔드포인트 6.
`POST /api/v1/school-camps`"

## 개요/목적
스쿨캠핑 도메인의 첫 이슈로, 이후 신청(#68)이 의존하는 `SchoolCampSession` 도메인 모델을
이 이슈에서 만든다. 아래 2개 엔드포인트를 다룬다.
1. `POST /api/v1/school-camps` — 관리자가 다음 달 신청 가능한 날짜를 일괄 등록
2. `GET /api/v1/school-camps?month=yyyyMM` — 캘린더용 날짜별 신청 현황 조회

신청(#68)이 아직 없는 시점이라, 2번 응답의 `teacherDisplayName`/`applicantDisplayName`은
이 이슈 범위에서는 항상 `null`이다(모든 세션이 `taken_at = null`이므로) — #68이 끝나면
자연히 채워진다.

## 도메인 모델 — `SchoolCampSession` (신규)
마이그레이션 `V11__add_schoolcamp_session.sql`:
```sql
CREATE TABLE school_camp_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    camp_date DATE NOT NULL UNIQUE,
    taken_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```
- `taken_at`이 `NULL`이면 아직 빈 날짜(`OPEN`), 값이 있으면 이미 신청이 성사된 날짜
  (`CLOSED`) — 마스터 기획서 "도메인 모델" 절 참고. 이 이슈에서는 `POST`로 생성할 때
  항상 `taken_at = NULL`로만 만든다(점유시키는 쪽은 #68의 신청 엔드포인트 몫).
- 팀 인원 상한(8명)은 이 엔티티에 두지 않는다 — #68에서 앱 레벨 상수(`MAX_TEAM_SIZE`)로
  검증한다(마스터 기획서 참고).

```java
package com.remake.gone.schoolcamp.entity;

@Entity
@Table(name = "school_camp_session")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SchoolCampSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "camp_date", nullable = false, unique = true)
  private LocalDate campDate;

  /** 신청이 성사되어 이 날짜가 점유된 시각. null이면 아직 신청 가능한 빈 날짜. */
  @Column(name = "taken_at")
  private LocalDateTime takenAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
```

## 엔드포인트

### 1. `POST /api/v1/school-camps` — 다음 달 날짜 일괄 등록 (관리자)
**권한**: `ADMIN`(`@PreAuthorize("hasRole('ADMIN')")`)

**요청**
```json
{ "campDates": ["20260403", "20260410", "20260417", "20260424"] }
```
`campDates`: `yyyyMMdd` 문자열 목록, 최소 1개(Bean Validation `@NotEmpty`).

**응답** (`201 Created`)
```json
{
  "success": true,
  "data": [
    { "sessionId": 12, "campDate": "20260403" },
    { "sessionId": 13, "campDate": "20260410" }
  ],
  "message": "스쿨캠핑 일정이 등록되었습니다.",
  "code": null
}
```

**구현 로직** (마스터 기획서 6번 엔드포인트 그대로, "하나라도 위반 시 전체 거부"가 핵심)
1. `campDates`를 전부 `LocalDate`로 파싱(형식 오류는 Bean Validation 공통 처리로 `400`)
2. 각 날짜의 요일이 금/토/일이면 하나라도 있으면 전체 거부 — `SCHOOLCAMP_005`
3. `sessionRepository.existsByCampDateIn(dates)`로 이미 등록된 날짜가 하나라도 있으면 전체
   거부 — `SCHOOLCAMP_006`(부분 성공 없음, 관리자가 전부 고쳐서 재시도하는 편이 명확하다는
   마스터 기획서 판단 그대로 따름)
4. 검증을 전부 통과하면 각 날짜마다 `SchoolCampSession.builder().campDate(date).build()`
   생성 후 `saveAll`
5. 응답 DTO 리스트 변환(`sessionId`/`campDate`)

**에러**
- 요청에 금/토/일 요일이 포함됨 → `400` `SCHOOLCAMP_005`
- 이미 등록된 날짜가 포함됨 → `409` `SCHOOLCAMP_006`
- `ADMIN`이 아닌 계정 호출 → `403` `COMMON_003`(`@PreAuthorize` 거부, 기존 공통 처리)

### 2. `GET /api/v1/school-camps?month=yyyyMM` — 캘린더용 날짜/신청 현황 조회
**권한**: `isAuthenticated()`(학생/선생님/관리자 누구나)

**요청**: `month`(쿼리, 필수, `yyyyMM` 6자리 문자열)

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": [
    { "sessionId": 12, "campDate": "20260403", "status": "OPEN", "teacherDisplayName": null, "applicantDisplayName": null },
    { "sessionId": 13, "campDate": "20260410", "status": "CLOSED", "teacherDisplayName": "정문경", "applicantDisplayName": "3218정문경" }
  ],
  "message": "스쿨캠핑 일정을 조회했습니다.",
  "code": null
}
```

**`teacherDisplayName`/`applicantDisplayName` 표시 형식(확정)**: 프론트가 조립하지 않고
백엔드가 최종 문자열을 그대로 계산해서 보낸다.
- `teacherDisplayName`: 선생님 실명 그대로(`Gbsw.name`) — 학번 개념이 없으므로 이름만.
- `applicantDisplayName`: **학번(학년+반+번호) + 실명**을 붙인 한 문자열(예: 3학년 2반
  18번 정문경 → `"3218정문경"`) — 별도 필드로 안 쪼갠다.
  - 이 형식은 `AuthService.generateStudentDefaultName`이 회원가입 기본 닉네임을 만들 때
    쓰는 포맷(`"%d%d%02d%s".formatted(grade, classNo, number, name)`)과 완전히 같다 —
    다만 목적이 다르다(하나는 "회원가입 시 부여하는 최초 닉네임", 하나는 "관리자/선생님이
    보는 표시용 라벨"이라 우연히 같은 포맷일 뿐, 개념적으로 같은 값을 재사용하는 게
    아니다). 이 포맷 문자열(반이 10개 미만이라는 전제로 학년 1자리+반 1자리+번호 2자리
    0채움 = 고정 4자리)이 두 곳에 따로 있으면 나중에 한쪽만 고치는 실수가 날 수 있어,
    `gbsw.utils.GbswUtils.studentNumber(Gbsw)`(신규, 학번 4자리 문자열만 반환)로 포맷
    자체는 공유하고 각 호출부는 자기 목적에 맞게 이어붙인다 — 다만 **이 유틸은 #68이
    실제로 `applicantDisplayName`을 계산할 때 만든다**(이 이슈엔 그 값을 채우는 코드가
    없어서 아직 쓸 곳이 없다). `AuthService`를 이 유틸을 쓰도록 바꾸는 것도 #68의 몫으로
    남긴다(#67 범위 아님 — 계산 로직 자체가 없는 이 이슈에서 미리 리팩터링하면 사용처
    없는 코드가 된다).

**구현 로직** (마스터 기획서 1번 엔드포인트 그대로)
1. 컨트롤러가 `@RequestParam @DateTimeFormat(pattern = "yyyyMM") YearMonth month`로 직접
   바인딩한다 — `outing`의 `dateFrom`/`dateTo`(`@DateTimeFormat(pattern = "yyyyMMdd")
   LocalDate`)와 동일한 패턴. 파싱 실패는 Spring이 `MethodArgumentTypeMismatchException`을
   던지고, `GlobalExceptionHandler`가 이미 이걸 `400` `COMMON_001`로 처리하고 있어(#41에서
   추가됨) **신규 에러 코드가 필요 없다** — 애초 계획했던 `SCHOOLCAMP_010`은 만들지 않는다.
2. `sessionRepository.findByCampDateBetween(month.atDay(1), month.atEndOfMonth())` 조회
3. 이 이슈 범위에서는 `SchoolCampApplication`이 아직 없으므로(⁠#68에서 추가), `status`는
   `taken_at` 유무로만 계산(`taken_at != null ? CLOSED : OPEN`)하고
   `teacherDisplayName`/`applicantDisplayName`은 항상 `null`로 채운다 — **이 두 필드를
   실제 신청 정보에서 채우는 로직(및 위 표시 형식 적용)은 #68이 담당**(그때 이 메서드에
   조인 쿼리가 추가된다).
4. DTO 리스트 변환

**에러**
- `month` 형식이 `yyyyMM`이 아님 → `400` `COMMON_001`(기존 공통 처리 재사용)

## 영향 받는 기존 코드
- 신규 패키지 `schoolcamp`: `entity/SchoolCampSession`, `repository/SchoolCampSessionRepository`,
  `service/SchoolCampService`, `controller/SchoolCampController`,
  `dto/RegisterSchoolCampDatesRequest`, `dto/SchoolCampSessionResponse`,
  `dto/SchoolCampCalendarResponse`, `exception/SchoolCampErrorCode`, `enums/SchoolCampStatus`
  (`OPEN`/`CLOSED`)
- `SchoolCampErrorCode`(신규): 이 이슈에서 실제로 쓰는 코드만 채운다 —
  `SCHOOLCAMP_005`(400, "신청 가능한 날짜가 아닙니다(금/토/일 포함)"),
  `SCHOOLCAMP_006`(409, "이미 등록된 날짜입니다"). `month` 형식 오류는 위에서 정리한 대로
  기존 `COMMON_001` 공통 처리로 충분해 신규 코드를 만들지 않는다. 마스터 기획서의 나머지
  코드(001~004/007~009)는 #68/#70이 실제로 그 분기에 도달할 때 각자 추가한다 — 지금 다
  채워두면 아직 쓰이지 않는 코드가 되어 checkstyle의 "미사용" 경고는 안 나지만(enum이라
  상수 자체는 안전) 리뷰 시 "이거 왜 여기서 안 쓰이지"라는 혼란을 줄 수 있어, 실제 사용
  시점에 맞춰 점진적으로 추가한다(#41/#42가 `OutingErrorCode`를 채운 방식과 동일).
- `V11__add_schoolcamp_session.sql`(신규 마이그레이션)
- `SecurityConfig`(수정 없음): `@PreAuthorize`가 이미 `outing`에서 활성화된
  `@EnableMethodSecurity`를 그대로 재사용 — 이 이슈에서 새로 켤 필요 없음(마스터 기획서
  "권한 모델" 절 참고)

## 리스크 및 고려사항
- **API 설계 6원칙 체크**:
  1. 한 가지를 잘하기 — 등록/조회 2개 엔드포인트로 범위가 좁다. 준수.
  2. 빠르게 시작 — 요청/응답 예시 포함. 준수.
  3. 직관적 일관성 — `/api/v1/school-camps` 경로, `ApiResponse<T>`, `SCHOOLCAMP_NNN`
     에러 코드 컨벤션 그대로 따름.
  4. 의미 있는 오류 — 실패 원인별로 코드 분리(`005`/`006`/`010`).
  5. 확장성/성능 — 캘린더 조회는 한 달치라 결과가 최대 31건으로 상한이 명확해
     페이지네이션 불필요(넣지 않기로 결정, 근거: 데이터 크기 상한이 이미 요일 수로
     자연스럽게 제한됨).
  6. 하위 호환성 — 전부 신규 엔드포인트, 해당 없음.
- **`taken_at` 컬럼이 이 이슈에서는 항상 `NULL`인 채로 끝난다** — #68(신청 API)이 머지되기
  전까지는 실제로 점유되는 세션이 생기지 않는다. QA 시 이 점을 감안해 "CLOSED 케이스는
  #68 이후 재검증 필요"로 남긴다(이 이슈만으로는 CLOSED 응답을 실제로 재현할 방법이 없어
  DB에 직접 `taken_at`을 채워 넣고 확인하는 수동 검증이 필요).
- **금/토/일 검증과 타임존**: `LocalDate.getDayOfWeek()`는 타임존 개념이 없어(순수 날짜)
  `outing`처럼 KST 변환이 필요 없다 — 요청받은 날짜 문자열 자체의 요일만 보면 된다.

## 테스트
- `POST /api/v1/school-camps`: 정상 등록, 금/토/일 포함 시 전체 거부, 중복 날짜 포함 시
  전체 거부, `ADMIN` 아닌 계정 접근 거부
- `GET /api/v1/school-camps?month=`: 정상 조회(빈 달/일부 등록된 달), `taken_at` 있는
  세션은 `status=CLOSED` + 두 이름 필드 `null`로 나오는지(#68 이전이므로), 잘못된 `month`
  형식 거부
