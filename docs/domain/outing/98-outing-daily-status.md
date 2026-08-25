# #98 외출증 오늘 전체 현황 조회 API — 기획서

관련 이슈: [#98 외출증 오늘 전체 현황 조회 API (관리용)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/98)
마스터 기획서: [1_outing-domain.md](./1_outing-domain.md) (11번 엔드포인트)
선행 코드: [`OutingController`](../../../src/main/java/com/remake/gone/outing/controller/OutingController.java)/
[`OutingService`](../../../src/main/java/com/remake/gone/outing/service/OutingService.java)/
[`OutingRepository`](../../../src/main/java/com/remake/gone/outing/repository/OutingRepository.java)
직전 이슈(설계 재사용 대상): [96-outing-active-list.md](./96-outing-active-list.md)

## 개요/목적
Issue #96(`GET /active`)은 "지금 나가있는 사람만" 보여준다. 이 이슈는 그와 반대로 **하루치
전체 흐름**(대기/승인/거절/출발/도착/마감 전부 포함)을 한눈에 보여주는 관리용 조회다.
선도부/선생님이 "오늘 외출 신청이 총 몇 건이고 각각 어떤 상태인지"를 파악하는 용도다.

## 마스터 기획서 재검토 (api-design.md "마스터 기획서 재검토" 원칙 적용)
- **권한 체크 방식**: #96과 동일하게 `@PreAuthorize("hasAnyRole('DISCIPLINE', 'TEACHER',
  'ADMIN')")`를 컨트롤러에 붙인다.
- **페이지네이션 방식**: 마스터 기획서는 페이지네이션을 언급하지 않았지만, #96에서 이미
  같은 도메인의 모든 목록 엔드포인트를 DB 레벨 페이지네이션(Spring Data `Page<T>`)으로
  통일했다. 이 엔드포인트도 그 컨벤션을 그대로 따른다 — `PageResponse.of(Page<T>)` 재사용.
- **`status` 필터의 유효 상태(MISSED) 처리**: `/me/requests`/`/me/received`(#41)와
  `/active`(#96)는 전부 `status` 필터가 "조회 시점에 계산되는 유효 상태" 기준으로
  동작한다(`PENDING`이 마감을 넘기면 DB 값은 그대로 두고 응답에서만 `MISSED`로 표시).
  이 엔드포인트만 다르게 갈 이유가 없어, 동일하게 `OutingQueryStatus` +
  `statusEq`/`wantExpired` 파라미터 패턴(#96에서 도입)을 재사용한다.
- **인덱스 — #96과 다르게 간다(이번엔 추가한다)**: #96은 `status`만으로 필터링해서
  인덱스 없이도(현재 데이터량에서는) 감당 가능하다고 보고 인덱스를 성능 백로그(#109)로
  미뤘다. 이 엔드포인트는 다르다 — **특정 학생/선생님으로 좁혀지지 않는, 하루 전체
  학생을 대상으로 한 조회**라 테이블 전체를 훑는 성격이 #96보다 강하고, 마스터
  기획서(11번 절)가 이미 명시적으로 `(outing_date, status)` 인덱스를 요구하고 있다.
  기존 인덱스(`idx_outing_student_date(student_user_id, outing_date)`)는
  `student_user_id`가 선두 컬럼이라 이 쿼리(학생 조건 없음)에는 못 쓴다. 그래서 이번
  이슈 범위에 인덱스 마이그레이션을 포함한다(아래 "데이터 모델 변경" 참고) — #109로
  미루지 않는다.
- **응답 DTO**: #96과 달리 이 화면은 "하루치 전체 흐름"을 보여주는 게 목적이라 필드를
  좁힐 이유가 없다. 기존 `OutingResponse`(`/me/requests`, `/me/received`, `/{code}`가
  쓰는 것과 동일)를 그대로 재사용한다 — 새 DTO를 만들지 않는다.

## 엔드포인트

### `GET /api/v1/outings` — 오늘(또는 지정 날짜) 전체 외출증 현황
**권한**: `DISCIPLINE`, `TEACHER`, `ADMIN` 중 하나(`@PreAuthorize`)

**요청**
```http
GET /api/v1/outings?date=20260825&status=PENDING&page=0&size=20
```
- `date`: 선택, 기본값 오늘(KST), 형식 `yyyyMMdd`(형식이 아니면 Spring 바인딩 단계에서
  400 — `/me/requests`의 `dateFrom`/`dateTo`와 동일한 처리)
- `status`: 선택, `OutingQueryStatus`(`PENDING`/`APPROVED`/`REJECTED`/`DEPARTED`/
  `RETURNED`/`MISSED`). 생략 시 전체 상태 반환
- `page`: 선택, 기본값 `0`
- `size`: 선택, 기본값 `20`, `1~100`

**응답** (`200 OK`) — 기존 `OutingResponse`와 동일 스키마(필드 목록은
[41-outing-query.md](./41-outing-query.md) 참고), `PageResponse<OutingResponse>`로
페이지네이션.

**구현 로직** (`OutingService.getDailyOverview`)
1. `page`/`size` 검증 — 기존 `validatePageParams` 재사용
2. `date`가 없으면 `today`(파라미터로 받은 "오늘")를 사용
3. `statusFilter`(`OutingQueryStatus`)를 `resolveStatusFilterParams`(#96에서 만든 private
   헬퍼, 그대로 재사용)로 `statusEq`/`wantExpired`로 변환
4. `outingRepository.findByOutingDatePage(date, statusEq, wantExpired, today, now,
   pageable)` 조회(신규 메서드, `Page<Outing>` 반환) — `Pageable`의 정렬은 `startTime`
   오름차순 + `id` 보조 정렬(하루 안에서 이른 시간대 먼저 보이도록. `#96`의
   `departedAt` 정렬과 달리 이 화면은 특정 날짜 하루로 이미 좁혀져 있어 `outingDate`
   정렬은 무의미하고, `startTime`이 자연스러운 하루 스케줄 순서)
5. `Page<Outing>.map(...)`으로 `OutingResponse` 변환(기존 `toResponse` 재사용) 후
   `PageResponse.of(Page<T>)`로 감싸 반환

## 데이터 모델 변경
`outing` 테이블에 `(outing_date, status)` 복합 인덱스를 추가하는 마이그레이션 1개
(`V{타임스탬프}__add_outing_date_status_index.sql`, 버전 형식은
[migration-convention.md](./migration-convention.md) 참고 — 커밋 시점의 KST 타임스탬프로
확정). 엔티티/컬럼 변경은 없다.

`OutingRepository`에 신규 메서드 추가:
- `findByOutingDatePage(LocalDate date, OutingStatus statusEq, Boolean wantExpired,
  LocalDate today, LocalTime now, Pageable pageable): Page<Outing>` — `#96`의
  `findStudentRequestsPage`/`findTeacherReceivedPage`와 동일한 `statusEq`/`wantExpired`
  파라미터 규칙을 쓰되, `student.id`/`teacher.id` 조건이 없고 `outingDate BETWEEN`이
  아니라 `outingDate = :date`(단일 날짜) 조건이라는 점만 다르다.

## 영향 받는 기존 코드/테스트
- 신규: `OutingController.getDailyOverview`, `OutingService.getDailyOverview`,
  `OutingRepository.findByOutingDatePage`, 인덱스 마이그레이션 1개
- 재사용(변경 없음): `OutingResponse`, `OutingService.resolveStatusFilterParams`/
  `toResponse`/`validatePageParams`, `PageResponse.of(Page<T>)`, `OutingQueryStatus`
- 변경 없음: `Outing` 엔티티, 기존 다른 엔드포인트

## 리스크 및 고려사항
- **API 설계 6원칙**:
  1. 한 가지를 잘하기: "하루 전체 흐름 조회"라는 단일 목적, 기존 `OutingResponse` 재사용 —
     부합.
  4. 의미 있는 오류: 새 에러 코드 없이 기존 `COMMON_002`/`COMMON_003`/`OUTING_015` +
     Spring 바인딩 400 재사용.
  5. 확장성/성능: `(outing_date, status)` 인덱스 추가로 반영(위 "마스터 기획서 재검토"
     참고). N+1(student/student.gbsw LAZY)은 `#96`과 동일하게 남아있으나, 성능
     백로그(#109)에 이미 "outing 도메인 전반"으로 기록돼 있어 이 이슈에서 별도로 다시
     만들지 않는다.
  6. 하위 호환성: 새 엔드포인트라 기존 응답에 영향 없음. 다만 `GET /api/v1/outings`가
     `POST /api/v1/outings`(외출증 신청)와 같은 경로를 쓴다 — HTTP 메서드가 달라
     Spring 라우팅 충돌은 없다(REST 관례상 흔한 패턴).
- **`date` 범위 제한 없음(의도적)**: 마스터 기획서가 요구한 건 "기본값을 오늘로 강제"뿐이고
  조회 가능한 날짜 범위 자체를 제한하라는 요구는 없다. `#43`의 운영 원칙([[feedback_outing_loose_restrictions]]
  — 시스템은 느슨하게, 선도부/선생님이 실제 판단)에 따라, 과거 특정 날짜 조회를 막을
  이유가 없어 그대로 둔다(예: "지난주 목요일에 누가 마감을 놓쳤는지" 확인하는 용도로도
  쓰일 수 있음).

## 테스트
- `OutingServiceTest.GetDailyOverview`(신규 `@Nested`):
  - `date` 생략 시 오늘 날짜로 조회
  - `status` 생략 시 전체 상태 반환
  - `status=PENDING`/`MISSED` 필터가 `statusEq`/`wantExpired`로 올바르게 변환되는지
    (`#96`과 동일한 케이스 구성)
  - 결과 없을 때 `content: []`(200, `null` 아님)
  - 리포지토리가 돌려준 `Page` 메타데이터를 그대로 응답에 반영
  - `page`/`size` 파라미터 검증(`OUTING_015`)
- `OutingControllerTest.GetDailyOverview`(신규 `@Nested`): 쿼리 파라미터 위임, 기본값
  적용, `date`/`status` 형식 오류 시 400 확인
- `OutingDailyOverviewAuthorizationTest`(신규, 실 DB 기반): `DISCIPLINE`/`TEACHER`/
  `ADMIN` 각각 200, `STUDENT` 403, 인증 없음 401(기존
  `OutingActiveListAuthorizationTest`와 동일한 패턴)

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과
- Postman 컬렉션 반영
- (해당 시) Notion 기능정의서 반영
