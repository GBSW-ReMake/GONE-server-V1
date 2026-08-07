# #41 외출증 본인/담당/단건 조회 API — QA 결과

관련 기획서: [41-outing-query.md](./41-outing-query.md)
코드 리뷰 결과(9단계): [41-outing-query-code-review.md](./41-outing-query-code-review.md)

## 1. QA/QC (10단계)

### 자동 검증
- `./gradlew build` (test + checkstyleMain 포함): **통과** (기존 129개 + 신규 테스트 전부,
  코드 리뷰 반영 후 재실행 포함)

### 수동 검증 (실서버, `localhost:9091`, dev 프로필)
`user1`(STUDENT)/`teacher1`(TEACHER)/`testuser01`(역할 없음, 관계없는 사용자 대역) 계정으로
실제 요청을 보내 확인했다. `MISSED` 시나리오는 MySQL에서 기존 `PENDING` 레코드의
`start_time`을 과거로 직접 갱신해(신청 API는 과거 시각으로는 생성이 안 되므로) 재현 후
검증 완료 시점에 원복했다.

| # | 케이스 | 기대 | 결과 |
|---|---|---|---|
| 1 | `GET /me/requests` 기본 조회(학생) | 200, 신청한 건 전부 표시 | ✅ |
| 2 | `GET /me/received` 기본 조회(선생님) | 200, 배정된 건 전부 표시 | ✅ |
| 3 | `GET /{code}` 단건 상세(학생/선생님 각각) | 200 | ✅ |
| 4 | `status=PENDING` 필터 | 대기 중인 것만 | ✅ |
| 5 | `status=REJECTED` 필터 | 거절된 것만 | ✅ |
| 6 | `period=TODAY` | 오늘 것만 | ✅ |
| 7 | `period=THIS_MONTH` | 이번 달 전체 | ✅ |
| 8 | `period=CUSTOM` + `dateFrom`/`dateTo` 없음 | 400 `OUTING_014` | ✅ |
| 9 | `period=THIS_WEEK` + `dateFrom` 같이 옴 | 400 `OUTING_014` | ✅ |
| 10 | `period=CUSTOM`, `dateFrom > dateTo` | 400 `OUTING_013` | ✅ |
| 11 | `period=NOT_A_PERIOD`(잘못된 enum) | 400 `COMMON_001` | ✅ |
| 12 | **`MISSED` 판정**: `PENDING`+마감 지남을 DB에서 재현 후 단건/목록 조회 | `status: "MISSED"`, DB는 `PENDING` 그대로 | ✅ |
| 13 | `status=PENDING` 필터가 12번 건을 제외하는지 | 제외됨 | ✅ |
| 14 | `status=MISSED` 필터가 12번 건만 반환하는지 | 정확히 그 건만 | ✅ |
| 15 | 무관한 인증된 사용자(`testuser01`)로 `GET /{code}` | 403 `OUTING_007` | ✅ (코드 리뷰 1번 항목 실서버로 재확인) |
| 16 | 인증 없이 `GET /{code}` | 401 `COMMON_002` | ✅ |
| 17 | 존재하지 않는 code로 `GET /{code}` | 404 `OUTING_006` | ✅ |
| 18 | `STUDENT`로 `GET /me/received` 호출 | 403 `COMMON_003` | ✅ |
| 19 | `TEACHER`로 `GET /me/requests` 호출 | 403 `COMMON_003` | ✅ |
| 20 | 결과 없는 범위 조회 | `data: []`(`null` 아님) | ✅ |
| 21 | `page=0&size=2`(결과 3건 중) | `content` 2개, `totalElements:3`, `totalPages:2`, `hasNext:true` | ✅ |
| 22 | 위 상태에서 `page=1&size=2` | `content` 1개, `hasNext:false` | ✅ |
| 23 | 마지막 페이지 다음 페이지(`page=5&size=2`) | `content: []`, `hasNext:false`, 200(에러 아님) | ✅ |
| 24 | `page=-1` | 400 `OUTING_015` | ✅ |
| 25 | `size=0` | 400 `OUTING_015` | ✅ |
| 26 | `size=101` | 400 `OUTING_015` | ✅ |
| 27 | `page`/`size` 생략 | 기본값 `page:0`, `size:20` 적용 | ✅ |
| 28 | 위 21~27번을 `GET /me/received`(선생님)에서도 동일 재현 | 동일 동작 | ✅ |
| 29 | `page=999999999&size=100`, `page=2147483647&size=100`(코드 리뷰 6번, `int` 오버플로 재현) | 200, `content: []`, `hasNext: false`(수정 전엔 500) | ✅ |
| 30 | `GET /outings/`(빈 `code`, 코드 리뷰 8번, Postman 컬렉션 작성 중 발견) | 404 `COMMON_004`(수정 전엔 500 `COMMON_007`) | ✅ |

**코드 리뷰 2번(`status=DEPARTED`/`RETURNED` 거부) 재확인**: 코드 리뷰 직후 바로 수정했고
(`OutingQueryStatus` 도입), 실서버 수동 재현 대신 자동화 테스트(`OutingQueryStatusTest`,
`OutingControllerTest.GetMyRequests.returns400WhenStatusIsUnreachableValue`)로 커버됨을
`./gradlew build` 재실행으로 확인.

**Medium — 환경 제약으로 직접 검증 못 한 항목**: `DISCIPLINE`/`ADMIN` 역할로 `GET /{code}`
접근하는 경로는 로컬 DB에 해당 역할 테스트 계정이 없어 수동 e2e로 재현하지 못했다.
`OutingServiceTest.GetOutingDetail`의 `allowsDisciplineRole`/`allowsAdminRole` 단위
테스트로 대체 확인했다(#31과 동일한 판단).

**페이지네이션 추가(구현 중, 기획서 "페이지네이션" 절 참고)**: 목록 조회 두 엔드포인트에
`page`/`size`를 추가했다. 위 21~28번이 그 검증이다. 이 추가분은 별도로 독립 에이전트에게
재리뷰를 맡겼고(코드 리뷰 문서 "페이지네이션 추가분 재리뷰" 절 참고), `page`가 매우 크면
`page*size`가 `int` 오버플로되어 500이 나는 High 버그(6번)를 발견해 즉시 수정했다 —
29번이 그 재현/수정 확인.

**15단계(Postman) 작업 중 추가 발견**: 새 에러 케이스 요청을 Newman으로 실행하다가 빈
`{code}`로 호출하면 404가 아니라 500이 나는 것을 발견했다(코드 리뷰 8번,
`NoResourceFoundException` 미처리) — outing 도메인 한정 문제가 아니라 앱 전체 라우팅의
일반적인 gap이지만, 고치는 비용이 낮고 이미 같은 파일에 같은 패턴의 선례가 있어 같이
수정했다. 30번이 그 재현/수정 확인.

## 2. 결론

Critical 없음. High 1건(페이지네이션 재리뷰 6번, `int` 오버플로 → 500)은 즉시 수정하고
실서버 재현으로 확인 완료(29번). Medium 2건(코드 리뷰 1번은 실서버 재현으로 해소,
`DISCIPLINE`/`ADMIN` 계정 제약 1건은 기존 관례대로 단위 테스트 대체), 코드 리뷰 Low 6건 중
3건(2번 상태 필터, 7번 페이지 초과 테스트 커버리지, 8번 NoResourceFoundException)은 수정
완료, 나머지 3건(3~5번)은 보류(상세 사유는
[41-outing-query-code-review.md](./41-outing-query-code-review.md) 참고). 페이지네이션
추가분(21~29번)과 Postman 검증 중 발견(30번)도 전부 통과 — 추가 조치 없이 PR 진행
가능하다고 판단.
