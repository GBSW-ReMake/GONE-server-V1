# #96 외출증 실시간 목록 조회 API — 기획서

관련 이슈: [#96 외출증 실시간 목록 조회 API (지금 외출 중인 학생)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/96)
마스터 기획서: [1_outing-domain.md](./1_outing-domain.md) (8번 엔드포인트)
선행 코드: [`OutingController`](../../../src/main/java/com/remake/gone/outing/controller/OutingController.java)/
[`OutingService`](../../../src/main/java/com/remake/gone/outing/service/OutingService.java)/
[`OutingRepository`](../../../src/main/java/com/remake/gone/outing/repository/OutingRepository.java)

## 개요/목적
`DEPARTED` 상태는 #43에서 실제로 생기기 시작했지만, "지금 외출 중인 학생이 누구인지"를
한눈에 보여주는 목록 엔드포인트가 아직 없다. 예전에 선도부가 카톡방으로 수동 공유하던
정보를 대체하는 이 도메인의 핵심 "가시성" 기능이다.

## 마스터 기획서 재검토 (api-design.md "마스터 기획서 재검토" 원칙 적용)
- **권한 체크 방식**: 마스터 기획서 작성 당시엔 서비스 코드에서 역할을 직접 검사하는
  방식을 가정했을 수 있으나, #30 이후 이 컨트롤러의 모든 형제 엔드포인트가
  `@PreAuthorize`를 쓰고 있다. 이번에도 동일하게 `@PreAuthorize("hasAnyRole('DISCIPLINE',
  'TEACHER', 'ADMIN')")`를 컨트롤러에 붙인다. `ADMIN`은 "공통 전제로 항상 접근 가능"이라는
  이 프로젝트의 일반 원칙이 있지만, 실제로 이미 구현된 형제 엔드포인트(`SchoolCampController`
  의 `hasAnyRole('STUDENT', 'TEACHER')` 등)를 확인해보면 `hasAnyRole`에 명시적으로 나열되지
  않은 역할은 자동으로 통과되지 않는다 — 그래서 `ADMIN`을 반드시 목록에 명시적으로 포함한다.
- **페이지네이션 — 마스터 기획서와 다르게 간다(추가한다), 방식은 코드 리뷰에서 재확정**:
  마스터 기획서는 페이지네이션 없이 단순 배열로 응답하도록 가정했다("시계열 데이터량이
  크지 않다"는 9번 엔드포인트의 근거를 8번에도 암묵적으로 적용한 것으로 보인다). 재검토
  결과 이 가정은 근거가 약했다:
  1. 마스터 기획서 자체가 "`DEPARTED` 상태가 자정을 넘어도 자동으로 정리되지 않는다"는
     미해결 리스크를 명시하고 있다(8번 절 하단 콜아웃). 도착 보고를 안 하고 방치되는
     외출증을 자동으로 정리하는 스케줄러가 아직 없으므로, 이 목록이 이론상 무제한으로
     누적될 수 있다.
  2. #41 이후 이 도메인의 다른 목록 엔드포인트(`GET /me/requests`, `GET /me/received`)는
     전부 `PageResponse<T>` 페이지네이션을 쓰고 있어, 이 엔드포인트만 단순 배열로 응답하면
     같은 컨트롤러 안에서 패턴이 어긋난다(api-design.md "직관적 일관성" 원칙).

  페이지네이션을 추가하기로 한 최초 판단은 유효했지만, **구현 착수 전 코드 리뷰에서
  "어떻게 페이지네이션할 것인가"가 잘못 설계됐다는 게 드러났다**: 기존 `PageResponse.of(List,
  page, size)`는 이미 메모리에 올라온 전체 리스트를 `subList`로 자르기만 하는 in-memory
  방식이다(`/me/requests`, `/me/received`는 기간 필터로 결과 건수가 원래 작아 이 방식이
  괜찮았다). `/active`는 날짜 필터가 없는 엔드포인트라 이 전제가 성립하지 않아, in-memory
  방식을 그대로 썼다면 "무제한 누적을 막는 방어선"이라는 페이지네이션의 목적 자체가
  무력화됐을 것이다. 그래서 **DB 레벨 페이지네이션(`LIMIT/OFFSET`, Spring Data
  `Page<T>`)으로 방식을 바꾸고, 이미 있던 `/me/requests`/`/me/received`도 이번에 같이
  전환했다(보스 확정, 2026-08-25)** — 아래 "데이터 모델 변경"/"영향 받는 기존 코드" 절
  참고. `PageResponse`에 `of(Page<T>)` 팩토리를 추가했고, 기존 `of(List, page, size)`는
  다른 도메인(SchoolCamp)이 계속 쓰므로 그대로 둔다.
- **응답 DTO**: 마스터 기획서는 `OutingResponse`를 재사용하지 않고 전용의 더 좁은 필드
  집합(`code`/`studentNickname`/`studentProfileImageUrl`/`studentRealName`/`studentGrade`/
  `studentClassNo`/`reason`/`timeSlot`/`departedAt`/`endTime`)을 제시했다 — `teacherName`,
  `startTime`, `outingDate`, `status`, `rejectedReason`, `returnedAt`, `offSchedule`은
  뺀다. 이 판단을 그대로 따른다: 이 목록은 "지금 누가 밖에 있고 언제까지 들어와야 하는지"만
  빠르게 훑어보는 화면이라 필요 이상의 필드를 넣지 않는 게 api-design.md "한 가지를 잘하기"
  원칙에 맞는다. 새 응답 타입 `OutingActiveResponse`를 만든다(기존 `OutingResponse`는
  변경하지 않음 — 하위 호환성 영향 없음).

## 엔드포인트

### `GET /api/v1/outings/active` — 지금 외출 중인 학생 목록
**권한**: `DISCIPLINE`, `TEACHER`, `ADMIN` 중 하나(`@PreAuthorize`)

**요청**
```http
GET /api/v1/outings/active?page=0&size=20
```
- `page`: 선택, 기본값 `0`
- `size`: 선택, 기본값 `20`, `1~100`

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "code": "8A1zx9202n",
        "studentNickname": "길동이",
        "studentProfileImageUrl": "https://.../profile/1/abc.jpg?X-Amz-...",
        "studentRealName": "홍길동",
        "studentGrade": 3,
        "studentClassNo": 4,
        "reason": "치과 진료",
        "timeSlot": "LUNCH",
        "departedAt": "2026-08-14T12:31:05",
        "endTime": "13:40"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  },
  "message": "현재 외출 중인 학생 목록입니다.",
  "code": null
}
```

**구현 로직** (`OutingService.getActiveOutings`)
1. `page`/`size` 검증 — 기존 `validatePageParams`(`OutingErrorCode.INVALID_PAGE_PARAMS`,
   `OUTING_015`) 그대로 재사용
2. `Pageable`을 `departedAt` 오름차순 + `id` 오름차순(보조 정렬 키) 정렬로 구성
3. `outingRepository.findByStatus(OutingStatus.DEPARTED, pageable)` 조회 — DB가
   `LIMIT/OFFSET`으로 이미 페이지 단위로 잘라서 반환한다(신규 메서드, `Page<Outing>` 반환)
4. `Page<Outing>.map(...)`으로 `OutingActiveResponse`로 변환(`R2FileService.
   generateDownloadUrl`로 프로필 이미지 URL 생성, 기존 `toResponse`와 동일한 패턴)
5. `PageResponse.of(Page<T>)`(신규 팩토리)로 감싸 반환

`departedAt` 오름차순만으로는 안정 정렬이 보장되지 않는다 — 컬럼이 초 단위 정밀도라
점심시간처럼 여러 학생이 같은 초에 출발하면 페이지 경계에서 학생이 누락될 수 있어, `id`를
보조 정렬 키로 추가했다(코드 리뷰에서 확인, 보스 확정).

**에러**
- 401 `COMMON_002` UNAUTHORIZED — 인증 안 됨
- 403 `COMMON_003` FORBIDDEN — `DISCIPLINE`/`TEACHER`/`ADMIN` 중 어느 것도 아님
- 400 `OUTING_015` INVALID_PAGE_PARAMS — `page` 음수 또는 `size`가 `1~100` 범위 밖

## 데이터 모델 변경
없음(엔티티/마이그레이션 불필요). `status` 컬럼 인덱스 부재는 인지하고 있으나 이번
범위에서 넣지 않고 #109(성능 백로그)로 미룬다 — 현재 데이터량에서 체감 영향이 없고,
인덱스는 나중에 무중단으로 추가할 수 있다(코드 리뷰, 보스 확정).

`OutingRepository`에 다음을 추가/변경한다(모두 DB 레벨 페이지네이션 전환의 일부,
"마스터 기획서 재검토" 절 참고):
- 신규: `findByStatus(OutingStatus status, Pageable pageable)` — `/active` 조회용,
  `Page<Outing>` 반환
- 신규: `findStudentRequestsPage(...)`, `findTeacherReceivedPage(...)` — 기존
  `findByStudentIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc`/
  `findByTeacherIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc`(List 반환,
  #41)를 대체. `@Query` JPQL로 날짜 범위 + "유효 상태" 필터(`statusEq`/`wantExpired`
  두 파라미터로 표현 — `PENDING`이 마감을 넘겨도 DB 값은 그대로 `PENDING`이라 단일 컬럼
  비교로 MISSED 여부를 가릴 수 없어서)까지 DB에서 처리하고 `Page<Outing>`을 반환한다
- 변경 없음: `findByStatus(OutingStatus status)`(List 반환, #42
  `OutingMissedScheduler` 전용 — 스케줄러는 페이지네이션 없이 대상 전체를 훑어야 해서
  그대로 둔다)

`common.response.PageResponse`에 `of(Page<T> page)` 팩토리를 추가한다. 기존
`of(List<T>, page, size)`는 SchoolCamp 도메인이 계속 쓰므로 그대로 둔다.

## 영향 받는 기존 코드/테스트
- 신규: `outing.dto.OutingActiveResponse`, `OutingController.getActiveOutings`,
  `OutingService.getActiveOutings`, `OutingRepository.findByStatus(status, Pageable)`,
  `PageResponse.of(Page<T>)`
- **변경(#96 범위를 넘는 리팩토링, 보스 확정)**: `OutingService.getMyRequests`/
  `getReceivedOutings`(#41)가 기존 in-memory 페이지네이션에서 DB 레벨 페이지네이션으로
  바뀐다. 응답 계약(요청 파라미터, 응답 스키마, 필터링 결과)은 동일하게 유지되고 내부
  구현 방식만 바뀐다 — `OutingControllerTest`/`OutingActiveListAuthorizationTest` 등
  기존 인가/계약 테스트는 변경 없이 통과해야 한다.
- **변경**: `OutingService.validateDetailAccess`(#41) — 담당 여부와 무관하게 `TEACHER`
  역할이면 단건 상세조회(`GET /{code}`)를 허용하도록 확장(아래 "리스크 및 고려사항" 참고)
- 변경 없음: `Outing` 엔티티, 기존 `OutingResponse`, `findByStatus(OutingStatus)`(#42
  스케줄러용), 그 외 엔드포인트

## 리스크 및 고려사항
- **API 설계 6원칙**:
  1. 한 가지를 잘하기: 좁은 전용 응답 DTO, 위치 좌표 제외(#97에서 별도 권한으로 제공) —
     원칙에 부합.
  4. 의미 있는 오류: 기존 `OUTING_015`/`COMMON_002`/`COMMON_003` 재사용, 새 에러 코드
     불필요.
  5. 확장성/성능: 위 "마스터 기획서 재검토" 절에서 페이지네이션 추가로 이미 반영.
  6. 하위 호환성: 새 엔드포인트 + 새 DTO라 기존 응답에 영향 없음.
- **`DEPARTED` 무제한 누적(유령 데이터) — 날짜 필터로 가리지 않기로 확정(보스 확정,
  2026-08-25)**: 도착 보고 없이 방치된 외출증을 자동으로 정리하는 스케줄러가 아직 없어,
  "며칠 전에 나간 걸로 표시된 학생"이 계속 이 목록에 남을 수 있다. `departedAt >= 오늘
  00:00` 같은 날짜 필터로 이런 유령 데이터를 감출 수도 있었지만 **의도적으로 넣지
  않는다** — 이 프로젝트는 "시스템이 1차로 느슨하게 거르고, 선도부/선생님이 2차로 실제
  판단한다"는 원칙([[feedback_outing_loose_restrictions]] 참고)을 따르는데, 방치된
  외출증은 숨겨야 할 노이즈가 아니라 **선도부가 확인 전화를 걸어야 할 이상 신호**다.
  숨기면 자정을 넘겨 아직 안 들어온 진짜 외출 중인 학생까지 같이 사라질 위험도 있다.
  이미 정한 `departedAt` 오름차순 정렬(아래)이 사실상 이 문제의 답이다 — 가장 오래
  방치된 항목이 목록 맨 위로 올라와 선도부 눈에 먼저 띄는 구조이므로, 날짜 필터로
  가리면 오히려 이 정렬의 목적과 충돌한다. 근본적인 자동 정리는 이번 이슈 범위 밖이며
  #102(승인됐지만 미출발 마감 처리)와 같은 성격의 후속 과제로 남긴다.
- **정렬 기준**: `departedAt` 오름차순(가장 오래 나가 있는 학생 우선) + `id` 오름차순
  보조 정렬로 확정했다 — 마스터 기획서에는 정렬 기준이 명시돼 있지 않아 이번에 새로
  정했고, `id` 보조 정렬은 착수 후 코드 리뷰에서 "`departedAt`이 초 단위 정밀도라 동률
  시 페이지 경계에서 학생이 누락될 수 있다"는 지적을 반영해 추가했다.
- **단건 상세조회(`GET /{code}`) 인가 범위 확장(보스 확정)**: 기존 `validateDetailAccess`
  는 `DISCIPLINE`/`ADMIN` 또는 담당으로 지정된 `TEACHER`만 통과시켰다. `/active`가 이미
  전체 `TEACHER`에게 전교생의 실시간 외출 현황(실명/학년/반 포함)을 보여주는데, 단건
  조회만 담당 선생님으로 좁혀두면 인가 정책이 자기모순이라는 게 코드 리뷰에서 지적됐다.
  전 교사에게 전교생 현황을 여는 쪽으로 통일하기로 확정하고, 담당 여부와 무관하게
  `TEACHER` 역할이면 단건 조회도 허용하도록 이번 이슈에서 같이 반영한다.
- **N+1 쿼리 / `status` 컬럼 인덱스 부재**: 코드 리뷰에서 확인했지만 지금 당장 고치지
  않고 #109(외출 도메인 성능 최적화 백로그)로 미룬다. 현재 데이터량에서는 체감 영향이
  없다.

## 테스트
- `OutingServiceTest.GetActiveOutings`(신규 `@Nested`):
  - `departedAt` 오름차순 + `id` 보조 정렬로 리포지토리를 호출하는지 확인(`DEPARTED`
    필터는 리포지토리 메서드 시그니처 자체가 강제하므로 별도 케이스 불필요)
  - 결과 없을 때 `content: []`(200, `null` 아님)
  - 프로필 이미지 키가 없으면 URL `null`, 있으면 presigned URL 생성
  - 리포지토리가 돌려준 `Page` 메타데이터(`totalElements`/`totalPages`/`hasNext`)를
    그대로 응답에 반영
  - `page`가 음수이거나 `size`가 범위 밖이면 `OUTING_015`
- `OutingServiceTest.GetMyRequests`/`GetReceivedOutings`(기존, DB 페이지네이션 전환에
  맞춰 갱신): `statusFilter`가 `statusEq`/`wantExpired` 파라미터로 올바르게 변환되는지
  (PENDING/MISSED/직접 매치 상태 각각), 리포지토리가 돌려준 Page 메타데이터를 그대로
  옮기는지 검증
- `OutingServiceTest.GetOutingDetail`(기존, 신규 케이스 추가): 담당 아닌 `TEACHER`
  역할도 조회 가능한지(`allowsAnyTeacherRole`)
- `OutingControllerTest.GetActiveOutings`(신규 `@Nested`): 쿼리 파라미터 위임, 기본값
  적용 확인
- `OutingActiveListAuthorizationTest`(신규, `@SpringBootTest` 실 DB 기반):
  `DISCIPLINE`/`TEACHER`/`ADMIN` 각각 200, `STUDENT`로 요청 시 403, 인증 없이 요청 시
  401(기존 `OutingReceivedAuthorizationTest`와 동일한 패턴)

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과
- Postman 컬렉션 반영
