# #41 외출증 본인/담당/단건 조회 API — 코드 리뷰 & QA 결과

관련 기획서: [41-outing-query.md](./41-outing-query.md)

## 1. 코드 리뷰 (9단계, 독립 에이전트)

컨텍스트 격리를 위해 구현 대화 기록 없이 별도 에이전트를 띄워, `merge-base`(84d74f7) ~
`feat/#41-outing-query` 전체 diff와 기획서만 전달해 `code-review` 스킬로 리뷰했다.

**결과: Critical/High 없음.** 핵심 로직(기간 프리셋 계산, `MISSED` 실시간 판정, `status`
필터 적용 순서, `GET /{code}` 소유권/역할 인가, `period`/날짜 조합 검증)이 기획서와 일치하고
정확하게 구현됐음을 확인. `rejectOuting` 시그니처 변경과 `GlobalExceptionHandler`
수정(범위 밖 발견 후 동봉)도 기획서에 명시된 그대로이며 HTTP 계약 변경 없음을 확인.

- **Medium** — `GET /{code}`의 "무관한 인증된 사용자 → 403" 경로가 실제 필터 체인을 거치는
  통합 테스트로는 커버되지 않고 서비스 단위 테스트로만 검증됨. → **아래 2번 QA에서 실서버로
  직접 재현/확인**(역할 기반 401/403과 달리 소유권 기반 403은 실제 DB 데이터가 있어야
  재현 가능해, 자동화 통합 테스트 대신 수동 e2e로 대체 확인 — #31 때 `DISCIPLINE`/`ADMIN`
  테스트 계정 부재를 단위 테스트로 대체했던 것과 같은 판단).
- **Low** — `status` 쿼리 파라미터가 `DEPARTED`/`RETURNED`도 유효한 값으로 받아들임(기획서는
  필터 목록에서 뺀다고 했으나 구현은 응답 DTO와 같은 `OutingStatus` enum을 재사용). 기능적
  피해는 없음(그 값들로 필터링해도 있을 수 없는 상태라 항상 빈 배열 — 기획서가 예측한 대로).
  전용 필터 enum을 새로 만드는 비용 대비 실익이 낮아 보류.
- **Low** — 목록 조회 시 `Outing.student`/`teacher`(LAZY) N+1 가능성. 현재 규모(하루 처리
  건수가 자연히 적음, 기획서 "확장성/성능" 절 참고)에서는 무시할 수준으로 판단해 보류.
- **Low** — `status=REJECTED`/`APPROVED` 필터 조합, `getReceivedOutings`의 period 검증
  실패 경로에 대한 전용 단위 테스트 부재. 로직은 `getMyRequests`와 완전히 공유되므로 위험은
  낮음 — 아래 2번 QA에서 실서버로 대체 확인.

## 2. QA/QC (10단계)

### 자동 검증
- `./gradlew build` (test + checkstyleMain 포함): **통과** (기존 129개 + 신규 테스트 전부)

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
| 15 | 무관한 인증된 사용자(`testuser01`)로 `GET /{code}` | 403 `OUTING_007` | ✅ (코드 리뷰 Medium 항목 실서버로 재확인) |
| 16 | 인증 없이 `GET /{code}` | 401 `COMMON_002` | ✅ |
| 17 | 존재하지 않는 code로 `GET /{code}` | 404 `OUTING_006` | ✅ |
| 18 | `STUDENT`로 `GET /me/received` 호출 | 403 `COMMON_003` | ✅ |
| 19 | `TEACHER`로 `GET /me/requests` 호출 | 403 `COMMON_003` | ✅ |
| 20 | 결과 없는 범위 조회 | `data: []`(`null` 아님) | ✅ |

**Medium — 환경 제약으로 직접 검증 못 한 항목**: `DISCIPLINE`/`ADMIN` 역할로 `GET /{code}`
접근하는 경로는 로컬 DB에 해당 역할 테스트 계정이 없어 수동 e2e로 재현하지 못했다.
`OutingServiceTest.GetOutingDetail`의 `allowsDisciplineRole`/`allowsAdminRole` 단위
테스트로 대체 확인했다(#31과 동일한 판단).

## 3. 결론

Critical/High 없음. Medium 2건(코드 리뷰 발견 1건은 실서버 재현으로 해소, `DISCIPLINE`/`ADMIN`
계정 제약 1건은 기존 관례대로 단위 테스트 대체)과 Low 3건(모두 낮은 실사용 영향, 보류 판단)
— 추가 조치 없이 PR 진행 가능하다고 판단.
