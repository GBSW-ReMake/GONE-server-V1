# #69 스쿨캠핑 본인 참여 내역 조회 API — 기획서

관련 이슈: [#69 스쿨캠핑 본인 참여 내역 조회 API 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/69)
마스터 기획서: [1_schoolcamp-domain.md](./1_schoolcamp-domain.md)의 "3.
`GET /api/v1/school-camps/me` / `GET /api/v1/school-camps/applications/{id}`" 절(이 이슈
검토로 아래 "설계 변경" 내용을 마스터 기획서에도 반영해뒀다)
선행 이슈: [#68](./68-schoolcamp-application.md)(신청, 완료·머지됨 — 이 이슈가 의존하는
`SchoolCampApplication`/`SchoolCampMember` 엔티티를 그대로 재사용한다), [#70](./70-schoolcamp-cancel-update.md)
(취소/수정, 완료·머지됨 — `cancelledAt` 필드 + `/applications/{id}` 경로를 그대로
재사용한다)

## 개요/목적
엔드포인트 2개를 구현한다.
1. `GET /api/v1/school-camps/me` — 본인 참여 이력 **목록**(요약, 페이지네이션)
2. `GET /api/v1/school-camps/applications/{id}` — 신청 1건의 **상세**(팀원 전체 포함)

학생 본인이 대표로 신청했든 팀원으로 초대받았든, 또는 선생님이 담당으로 지정됐든(설계
변경 3), 지금까지 본인과 관련된 스쿨캠핑 이력을 먼저 목록으로 훑어보고, 특정 건을 골라
상세(담당 선생님/팀원 전체)를 확인하는 2단계 조회 흐름이다.

**설계 변경 1(검토 반영) — 필수 `month` → 선택 파라미터 + 페이지네이션 + 취소 이력 포함**:
마스터 기획서 초안은 `month=yyyyMM`을 필수로 받아 "그 달의 참여 현황"만, 그것도 유효한
(취소되지 않은) 신청만 보여주는 설계였다. 검토 중 아래로 확정해 범위를 넓혔다.
- `month`를 선택 파라미터로 바꾼다. 생략하면 전체 참여 이력을, 지정하면 그 달만
  필터링한다.
- 취소된 신청도 포함한다(`cancelledAt`으로 구분) — "참여 내역"이라는 목적상 취소 이력도
  자연스럽게 포함되어야 한다.
- 그 결과 한 번의 조회가 여러 건을 반환할 수 있게 되어, `outing` 도메인의
  `GET /api/v1/outings/me/requests`(`OutingController.getMyRequests`)와 동일한
  페이지네이션 패턴(`PageResponse<T>`, `page`/`size`, `INVALID_PAGE_PARAMS` 에러 코드)을
  그대로 가져와 쓴다.

**설계 변경 2(검토 반영) — 목록/상세 응답 분리**: 마스터 기획서 초안은 목록 항목마다
팀원 전체(`members`)를 그대로 중첩해서 보여주는 응답 하나만 계획했다. 이는
[api-design.md](../../rules/api-design.md) 원칙 5("컬렉션 안에 컬렉션을 중첩해서 반환하지
않는다 — 필요하면 별도 엔드포인트로 분리한다")에 그대로 어긋난다 — 또한 같은 도메인의
`getCalendar`(2번 엔드포인트, 세션 **목록** 조회)도 이미 `teacherDisplayName`/
`applicantDisplayName` 같은 요약 문자열만 보여줄 뿐 팀원 전체를 중첩하지 않는다(팀원 전체를
보여주는 건 `#68`/`#70`처럼 신청 1건에 대한 **단일 리소스** 응답에서만이다). 그래서 목록은
가벼운 요약 응답으로, 상세는 별도 엔드포인트(`GET /applications/{id}`, `#70`이 이미 쓰는
`/applications/{id}` 경로에 GET 메서드만 추가)로 분리한다 — 이슈 1개당 엔드포인트 1~2개
범위([issue-template.md](../../rules/issue-template.md)) 안에 들어온다.

**설계 변경 3(검토 반영, 2026-08-20) — 선생님도 같은 엔드포인트로 조회 가능**: 초안은
`STUDENT` 전용이었으나, 담당 선생님도 본인이 담당하는 스쿨캠핑 이력을 볼 수 있어야 한다는
요구로 두 엔드포인트 모두 `TEACHER` 역할도 허용하도록 넓힌다. 새 엔드포인트를 따로 만들지
않고 같은 `GET /me`/`GET /applications/{id}`를 그대로 재사용한다 — "본인과 관련된 스쿨캠핑
이력 조회"라는 목적 자체는 학생/선생님이 같고, 달라지는 건 "본인과 관련됐다"의 판정 기준뿐이다
(학생은 `SchoolCampMember` 행, 선생님은 `SchoolCampApplication.teacherUser`). `SchoolCampMyRole`에
`TEACHER` 값을 추가해 구분한다. 상세 구현은 아래 각 엔드포인트 절 참고.
- **가입된(`teacherUser`) 선생님만 대상**이다 — 자유 입력(`teacherName`)으로 지정된 담당
  선생님은 로그인 계정이 없어 애초에 이 API를 호출할 사람이 없다(기존 `teacherDisplayName`이
  가입 여부와 무관하게 표시용 이름 하나로 통일하는 것과 같은 이유로, 조회 주체 쪽에서는
  자연스러운 제약이다).
- 한 사람이 이론상 같은 신청에 학생(팀원)이면서 동시에 그 신청의 담당 선생님일 수는 없다
  (담당 선생님 지정은 `TEACHER` 역할 보유자만 가능하도록 `findValidTeacher`가 이미 검증하고,
  학교 운영상 겹칠 일이 없는 조합이라 별도 방어 로직을 넣지 않는다).

## 엔드포인트

### 1. `GET /api/v1/school-camps/me` — 본인 참여 내역 목록(요약)
**권한**: `STUDENT` 또는 `TEACHER`(컨트롤러 `@PreAuthorize("hasAnyRole('STUDENT','TEACHER')")`,
설계 변경 3 반영 — 본인 계정으로만 조회하므로 서비스 레벨 소유권 검증이 따로 필요 없다)

**요청** — 쿼리 파라미터
- `month` (선택, `yyyyMM`): 지정하면 그 달의 이력만, 생략하면 전체 이력을 조회한다
- `page` (선택, 기본 `0`): 페이지 번호(0부터 시작)
- `size` (선택, 기본 `20`, `1~100`): 페이지 크기

**응답** (`200 OK`) — `outing`의 `PageResponse<OutingResponse>`와 동일한 포맷
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 301,
        "campDate": "20260403",
        "teacherDisplayName": "박선생",
        "myRole": "APPLICANT",
        "appliedAt": "2026-03-20T09:12:00",
        "cancelledAt": null
      },
      {
        "id": 288,
        "campDate": "20260306",
        "teacherDisplayName": "김선생",
        "myRole": "MEMBER",
        "appliedAt": "2026-02-20T08:03:00",
        "cancelledAt": "2026-02-25T10:00:00"
      },
      {
        "id": 312,
        "campDate": "20260220",
        "teacherDisplayName": "박선생",
        "myRole": "TEACHER",
        "appliedAt": "2026-02-15T10:00:00",
        "cancelledAt": null
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 3,
    "totalPages": 1,
    "hasNext": false
  },
  "message": "스쿨캠핑 참여 내역을 조회했습니다.",
  "code": null
}
```
목록은 `campDate` 내림차순(최근 참여가 먼저)이다. `id`는 상세 조회(2번 엔드포인트)에 바로
쓸 수 있는 신청 PK다. 참여 내역이 없으면 `content: []`(에러 아님). `myRole: TEACHER`
항목(위 예시 세 번째)은 로그인 계정이 담당 선생님으로 지정된 세션이다 — 이 경우 응답의
`teacherDisplayName`은 항상 그 계정 본인의 실명과 같다(참고용으로만 남겨둔 필드이지 별도
의미는 없다).

**구현 로직**
1. `page`/`size` 유효성 검증(`page >= 0`, `1 <= size <= 100`) — `outing`의
   `validatePageParams`와 동일한 규칙, 위반 시 `400` `SCHOOLCAMP_012`
2. **학생 쪽 이력**: `month` 지정 여부에 따라 리포지토리 메서드를 나눠 호출한다(둘 다 신규):
   - 지정: `SchoolCampMemberRepository.findMyParticipationsInMonth(userId, monthStart, monthEnd)`
   - 생략: `SchoolCampMemberRepository.findMyParticipations(userId)`
   두 메서드 모두 `cancelledAt` 조건 없이(취소 포함) `campDate` 내림차순으로, 본인의
   `SchoolCampMember` 행 자체를 반환한다 — 그 행의 `isApplicant()`가 `APPLICANT`/`MEMBER`
   `myRole`이 된다.
3. **선생님 쪽 이력**(설계 변경 3, 신규): 마찬가지로 `month` 지정 여부에 따라
   `SchoolCampApplicationRepository.findByTeacherUserId(userId)` 또는
   `findByTeacherUserIdInMonth(userId, monthStart, monthEnd)`를 호출한다 — 취소 여부와
   무관하게, `campDate` 내림차순으로 본인이 담당 선생님으로 지정된 `SchoolCampApplication`을
   반환한다. 이쪽은 `myRole`이 항상 `TEACHER`다.
4. 2~3번 결과를 하나의 목록으로 합쳐 `campDate` 내림차순으로 다시 정렬한다(둘 다 이미
   내림차순이지만 병합 후 전체 순서를 보장해야 함) — 한 사용자가 그 달에 학생으로서의
   이력과 선생님으로서의 이력을 동시에 가질 일은 실질적으로 없지만(위 "설계 변경 3" 참고),
   있다 해도 시간순으로 자연스럽게 섞여 보이게 한다.
5. `PageResponse.of(...)`로 병합된 목록을 먼저 페이지를 자른 뒤, 그 페이지 안의 항목만
   요약 DTO로 변환한다(팀원 조회 없음 — 목록에는 애초에 필요 없다).
6. `teacherDisplayName`은 기존 `SchoolCampService.teacherDisplayName(application)` private
   메서드를 그대로 재사용한다(`getCalendar`가 쓰는 것과 동일 — `teacherUser`가 지정된
   항목은 지연 로딩 1회가 나가지만, 한 사람의 개인 이력이라 건수 자체가 작아
   `getCalendar`와 동일하게 특별히 최적화하지 않는다).

**에러**
- 인증 토큰 없음 → `401`
- `STUDENT`/`TEACHER` 둘 다 아닌 역할로 호출 → `403` `COMMON_003`
- `month` 형식이 `yyyyMM`이 아님 → `400` `COMMON_001`
- `page`가 음수이거나 `size`가 `1~100` 범위 밖 → `400` `SCHOOLCAMP_012`(신규)

---

### 2. `GET /api/v1/school-camps/applications/{id}` — 참여 신청 상세
**권한**: `STUDENT` 또는 `TEACHER` + 본인이 그 신청의 참여자(대표, 팀원, 또는 담당
선생님)여야 함(서비스 레벨 검증, 설계 변경 3 반영)

**요청**: 경로 변수 `id`(`SchoolCampApplication.id`, 1번 엔드포인트 응답의 `id`)

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "id": 301,
    "campDate": "20260403",
    "teacherDisplayName": "박선생",
    "myRole": "APPLICANT",
    "members": [
      { "studentRealName": "홍길동", "studentGrade": 3, "studentClassNo": 4, "guestName": null, "isApplicant": true },
      { "studentRealName": "이영희", "studentGrade": 3, "studentClassNo": 2, "guestName": null, "isApplicant": false }
    ],
    "appliedAt": "2026-03-20T09:12:00",
    "cancelledAt": null
  },
  "message": "스쿨캠핑 참여 상세를 조회했습니다.",
  "code": null
}
```

**구현 로직**
1. `applicationRepository.findById(id)`(기존 `JpaRepository` 기본 제공, 신규 메서드 아님)로
   신청을 조회한다 — `#70`의 `findByIdAndCancelledAtIsNull`과 달리 **취소 여부와 무관하게**
   조회한다(취소된 신청도 상세를 볼 수 있어야 하므로, 위 "설계 변경 1" 참고). 없으면 `404`
   `SCHOOLCAMP_010`(`APPLICATION_NOT_FOUND`, `#70`이 이미 추가한 코드 재사용)
2. `memberRepository.findByApplicationId(id)`(`#70`에서 이미 추가된 메서드 재사용)로 팀원
   전체를 조회하고, 그중 `studentUser.id`가 요청자 본인인 행을 찾는다.
   - 찾았으면 그 행의 `isApplicant()`로 `myRole`을 `APPLICANT`/`MEMBER`로 정한다.
   - 못 찾았으면(**설계 변경 3, 신규**) `application.getTeacherUser()`가 본인인지 확인한다
     — 맞으면 `myRole`을 `TEACHER`로 정한다.
   - 어느 쪽도 아니면(본인이 대표도, 팀원도, 담당 선생님도 아님) `403` `SCHOOLCAMP_013`
     (`NOT_APPLICATION_PARTICIPANT`) — `#70`의 `NOT_APPLICATION_OWNER`(`SCHOOLCAMP_007`,
     "본인 신청만 취소/수정할 수 있습니다")는 대표 신청자 전용 취소/수정 인가에 쓰는
     코드라 의미가 다르다(원인이 다르면 코드를 분리한다, 원칙 4).
3. DTO 변환 반환(`teacherDisplayName`/`toMemberResponse` 기존 private 메서드 재사용)

**에러**
- 인증 토큰 없음 → `401`
- `STUDENT`/`TEACHER` 둘 다 아닌 역할로 호출 → `403` `COMMON_003`
- 존재하지 않는 신청 → `404` `SCHOOLCAMP_010`
- 본인이 참여자(대표/팀원/담당 선생님 어느 쪽도)가 아닌 신청 조회 시도 → `403`
  `SCHOOLCAMP_013`(신규)

## 데이터 모델 변경
신규 엔티티/마이그레이션 없음(기존 `SchoolCampApplication`/`SchoolCampMember`만 조회).

### 신규 에러 코드
```java
// 1번 엔드포인트(목록) — page/size 검증 실패
INVALID_PAGE_PARAMS(
    HttpStatus.BAD_REQUEST, "SCHOOLCAMP_012", "페이지 파라미터가 올바르지 않습니다(page>=0, 1<=size<=100).");

// 2번 엔드포인트(상세) — 참여자가 아닌 신청 조회 시도
NOT_APPLICATION_PARTICIPANT(
    HttpStatus.FORBIDDEN, "SCHOOLCAMP_013", "본인이 참여한 신청만 조회할 수 있습니다.");
```
`INVALID_PAGE_PARAMS`는 `OutingErrorCode.INVALID_PAGE_PARAMS`(`OUTING_015`)와 메시지까지
동일하게 맞춘다(원칙 3) — 도메인마다 별도 `ErrorCode` enum을 쓰는 컨벤션상 코드 자체는
공유하지 않는다.

### 신규 DTO
```java
// 1번 엔드포인트(목록) — 팀원 없음
public record SchoolCampMyParticipationSummaryResponse(
    Long id,
    String campDate,
    String teacherDisplayName,
    SchoolCampMyRole myRole,
    String appliedAt,
    String cancelledAt
) {}

// 2번 엔드포인트(상세) — 팀원 전체 포함
public record SchoolCampMyParticipationResponse(
    Long id,
    String campDate,
    String teacherDisplayName,
    SchoolCampMyRole myRole,
    List<SchoolCampMemberResponse> members,
    String appliedAt,
    String cancelledAt
) {}
```
기존 `SchoolCampApplicationResponse`(`#68`/`#70`이 이미 계약으로 쓰는 응답)에 `myRole`/
`cancelledAt`을 끼워 넣지 않고 이 이슈 전용 DTO 2개를 새로 만든다 — 그 두 엔드포인트
입장에서는 항상 의미 없는 필드가 붙는 걸 피한다(원칙 3). 상세 응답의 `members`는 기존
`SchoolCampMemberResponse`를 그대로 재사용(변경 없음).

### 신규 enum — `SchoolCampMyRole` (`schoolcamp.enums` 패키지)
```java
public enum SchoolCampMyRole {
  APPLICANT,
  MEMBER,
  TEACHER
}
```
`TEACHER`는 설계 변경 3(선생님 조회 지원) 반영.

### `SchoolCampMemberRepository`에 추가할 조회 메서드
```java
@Query("select m from SchoolCampMember m "
    + "join fetch m.application a "
    + "join fetch a.session s "
    + "where m.studentUser.id = :userId "
    + "order by s.campDate desc")
List<SchoolCampMember> findMyParticipations(@Param("userId") Long userId);

@Query("select m from SchoolCampMember m "
    + "join fetch m.application a "
    + "join fetch a.session s "
    + "where m.studentUser.id = :userId "
    + "and s.campDate between :monthStart and :monthEnd "
    + "order by s.campDate desc")
List<SchoolCampMember> findMyParticipationsInMonth(
    @Param("userId") Long userId,
    @Param("monthStart") LocalDate monthStart,
    @Param("monthEnd") LocalDate monthEnd);
```
1번 엔드포인트(목록)의 학생 쪽 이력 조회에서만 쓴다. 기존 `findParticipatedStudentIdsInMonth`
(같은 파일, 이번 달 중복 참여 검증용)와 같은 조인 모양(`SchoolCampMember` → `application` →
`session`)을 재사용하되, **의도적으로 `cancelledAt IS NULL` 조건을 넣지 않는다**(취소 이력도
포함하는 이 이슈의 요구사항과 정반대이므로) — 검증용 쿼리와 조회용 쿼리가 같은 조인을 쓰지만
필터는 다르다는 점을 명확히 하기 위해 기존 메서드를 고치지 않고 새 메서드 2개를 추가한다.
2번 엔드포인트(상세)는 이 메서드들을 쓰지 않고, 기존 `findByApplicationId` +
`applicationRepository.findById`(둘 다 이미 존재)만으로 충분하다.

### `SchoolCampApplicationRepository`에 추가할 조회 메서드(설계 변경 3, 신규)
```java
@Query("select a from SchoolCampApplication a "
    + "join fetch a.session s "
    + "where a.teacherUser.id = :teacherUserId "
    + "order by s.campDate desc")
List<SchoolCampApplication> findByTeacherUserId(@Param("teacherUserId") Long teacherUserId);

@Query("select a from SchoolCampApplication a "
    + "join fetch a.session s "
    + "where a.teacherUser.id = :teacherUserId "
    + "and s.campDate between :monthStart and :monthEnd "
    + "order by s.campDate desc")
List<SchoolCampApplication> findByTeacherUserIdInMonth(
    @Param("teacherUserId") Long teacherUserId,
    @Param("monthStart") LocalDate monthStart,
    @Param("monthEnd") LocalDate monthEnd);
```
1번 엔드포인트(목록)의 선생님 쪽 이력 조회에서만 쓴다. `SchoolCampMember`를 거치지 않고
`SchoolCampApplication.teacherUser`를 직접 조회한다는 점만 다르고, 나머지(취소 포함,
`campDate` 내림차순)는 학생 쪽 쿼리와 동일한 규칙이다.

## 영향 받는 기존 코드
- 신규: `SchoolCampMyParticipationSummaryResponse`/`SchoolCampMyParticipationResponse`
  (DTO), `SchoolCampMyRole`(enum, `TEACHER` 포함)
- 수정: `SchoolCampErrorCode`(`INVALID_PAGE_PARAMS` = `SCHOOLCAMP_012`,
  `NOT_APPLICATION_PARTICIPANT` = `SCHOOLCAMP_013` 추가), `SchoolCampMemberRepository`
  (`findMyParticipations`/`findMyParticipationsInMonth` 추가), `SchoolCampApplicationRepository`
  (`findByTeacherUserId`/`findByTeacherUserIdInMonth` 추가, 설계 변경 3), `SchoolCampService`
  (`getMyParticipations`/`getMyParticipationDetail` 추가 — `MIN_PAGE_SIZE`/
  `MAX_PAGE_SIZE` 상수와 `validatePageParams`도 `outing`과 동일하게 추가,
  `teacherDisplayName`/`toMemberResponse` 기존 private 메서드는 그대로 재사용),
  `SchoolCampController`(`GET /me`, `GET /applications/{id}` 추가, 둘 다
  `@PreAuthorize("hasAnyRole('STUDENT','TEACHER')")`)
- **마스터 기획서(`1_schoolcamp-domain.md`) 3번 절도 이번 검토 결과대로 갱신** — 이
  프로젝트는 보통 마스터 기획서를 매번 고치지 않고 이슈별 기획서만 최신으로 유지하는 게
  원칙([api-design.md](../../rules/api-design.md) "마스터 기획서 재검토" 참고)이지만,
  이번엔 초안 자체(단일 엔드포인트·`month` 필수·취소 제외·목록에 팀원 중첩)가 실제
  결정과 정반대로 남아있으면 다음에 이 절을 읽는 사람이 혼동하기 쉬워 예외적으로 마스터
  문서도 함께 손봤다
- 신규 마이그레이션 없음

## 리스크 및 고려사항
- **API 설계 6원칙 체크**:
  1. 한 가지를 잘하기 — 목록(요약)과 상세를 엔드포인트 2개로 분리해 각각 한 가지
     사용 사례만 책임진다(위 "설계 변경 2" 참고).
  2. 빠르게 시작 — 요청/응답 예시 포함. 준수.
  3. 직관적 일관성 — `outing`의 `getMyRequests`와 페이지네이션 파라미터/응답 포맷/에러
     코드 메시지까지 동일한 패턴을 맞췄고, 상세 응답은 `getCalendar`가 이미 쓰는
     "목록은 요약, 단일 리소스는 상세"라는 이 도메인 자체의 관례를 따른다.
  4. 의미 있는 오류 — 페이지 파라미터 오류(`SCHOOLCAMP_012`), 참여자 아님(`SCHOOLCAMP_013`)
     을 기존 코드와 원인별로 분리했다.
  5. 확장성/성능 — **컬렉션 중첩을 없앤 것 자체가 이 원칙 대응이다**(위 "설계 변경 2").
     목록 조회는 `outing.getMyRequests`와 동일하게 "본인 이력 전체를 가져온 뒤 메모리에서
     자르는" 방식을 쓴다 — 전역 검색이 아니라 항상 `studentUser.id = 로그인한 본인`으로
     좁혀지는 개인 이력이라, 한 학생의 평생 참여 횟수 자체가 자연히 작다(스쿨캠핑은
     학생당 최대 월 1회, 재학 기간 전체를 합쳐도 수십 건 규모).
  6. 하위 호환성 — 신규 엔드포인트라 해당 없음.
- **월 경계 밖 이력**: `month`를 지정하면 그 달만 보이는 게 의도된 동작이다.
- **취소된 신청도 보이는 것이 의도된 동작**(설계 변경으로 확정) — 목록/상세 모두 취소
  이력을 포함한다. 취소 자체를 취소하거나 되돌리는 기능은 이 이슈 범위 밖이다.
- **상세 조회 인가가 "대표 신청자만"이 아니라 "참여자 전체"** — `#70`의 취소/수정은 대표
  신청자만 할 수 있지만(팀원은 자기 참여를 취소/수정할 권한이 없음, 마스터 기획서가 이미
  확정한 정책), 이 이슈의 "조회"는 읽기 전용이라 팀원도, 담당 선생님도 자신이 관련된
  신청의 상세를 볼 수 있게 하는 것이 자연스럽다고 판단했다 — 취소/수정 가능 여부와 조회
  가능 여부를 다른 기준으로 가져가는 것이다.
- **선생님 조회는 가입된(`teacherUser`) 계정만 가능**(설계 변경 3) — 자유 입력
  (`teacherName`)으로 지정된 담당 선생님은 로그인 계정 자체가 없어 이 API를 호출할 수
  없다. 그 경우 학생 쪽에서는 여전히 목록/상세를 볼 수 있으니 정보 자체가 사라지는 건
  아니고, "그 선생님 본인이 자기 목록에서 조회"만 안 되는 제약이다.
- **한 사람이 학생/선생님 이력을 동시에 가지는 경우는 실질적으로 없다**(설계 변경 3) —
  담당 선생님 지정은 `TEACHER` 역할 보유자만 가능(`findValidTeacher`가 이미 검증)하고,
  이 학교 운영상 학생이면서 동시에 스쿨캠핑 담당 선생님인 계정은 나오지 않는다고 보고
  별도 방어 로직 없이 단순 병합·정렬만 한다.

## 테스트
- `SchoolCampService.getMyParticipations`(목록):
  - 참여 내역이 없음 → 빈 `content`
  - 대표로 참여한 신청이 있음 → `myRole: APPLICANT`
  - 팀원으로 초대받은 신청이 있음 → `myRole: MEMBER`
  - **담당 선생님으로 지정된 신청이 있음 → `myRole: TEACHER`**(설계 변경 3 핵심 검증)
  - **학생 이력 + 선생님 이력이 함께 있으면 `campDate` 기준으로 올바르게 병합·정렬되는지**
    (설계 변경 3 핵심 검증)
  - 취소된 신청도 목록에 포함되고 `cancelledAt`이 채워지는지(설계 변경 1 핵심 검증)
  - `month`를 지정하면 그 달만, 생략하면 전체 이력이 조회되는지
  - `campDate` 내림차순으로 정렬되는지
  - 페이지네이션(`page`/`size`)이 올바르게 적용되는지
  - `page < 0` 또는 `size`가 범위 밖(`0`, `101` 등) → `400` `SCHOOLCAMP_012`
- `SchoolCampService.getMyParticipationDetail`(상세):
  - 대표로 참여한 신청 조회 → `myRole: APPLICANT`, `members`에 전체 팀원 포함
  - 팀원으로 참여한 신청 조회 → `myRole: MEMBER`
  - **담당 선생님으로 지정된 신청 조회 → `myRole: TEACHER`**(설계 변경 3 핵심 검증)
  - 취소된 신청도 상세 조회가 되는지(설계 변경 1 핵심 검증)
  - 존재하지 않는 신청 → `404`
  - 본인이 참여자(대표/팀원/담당 선생님 어느 쪽도)가 아닌 신청 조회 → `403` `SCHOOLCAMP_013`
- `SchoolCampController`/인가: 두 엔드포인트 모두 `STUDENT`/`TEACHER` 둘 다 아닌 역할(예:
  `ADMIN` 단독)로 호출 시 `403`(`SchoolCampAuthorizationTest`에 이미 있는 패턴 확장), 인증
  토큰 없이 호출 시 `401`
