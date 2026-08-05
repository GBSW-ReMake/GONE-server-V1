# #26 나이스(NEIS) API 연동 — QA/코드 리뷰 결과

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/26
관련 기획서: [26-neis-meal-timetable.md](./26-neis-meal-timetable.md)
관련 후속 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/27 (3학년 학과 매핑 재검증)

## 자동 테스트
- `./gradlew build`(`checkstyleMain`, `checkstyleTest`, `test` 포함) 로컬 통과.
- 신규 테스트 18개:
  - `NeisClientTest` — NEIS 응답 3가지 형태(정상/`INFO-200` 빈 데이터/`ERROR-*` 진짜 에러)와
    네트워크 실패를 `MockRestServiceServer`로 재현해 파싱/예외 변환 검증
  - `MealServiceTest` — 캐시 히트/미스, `<br/>` 요리명 분리, `mealType` 필터링, 빈 데이터 케이스
  - `MealControllerTest` — 쿼리 파라미터 전달, 날짜 파싱 실패 케이스
  - `TimetableServiceTest` — 캐시 히트/미스, 1·2학년(학과 필터 없음)/3학년(하드코딩 매핑)
    분기, 교시 정렬, 선생님 계정 400, 존재하지 않는 사용자 401
  - `TimetableControllerTest` — 인증된 `userId`가 서비스에 그대로 전달되는지 확인

## QA — 실제 환경 검증

### 검증한 것
- 로컬 서버(`./gradlew bootRun`, 로컬 MySQL/Redis 대상)를 띄우고 실제 NEIS API까지 왕복하는
  라이브 호출로 확인:
  - `GET /api/v1/meals?date=20260810` — 조식/중식/석식 전체 응답이 기획서에 적어둔 예시와
    완전히 동일하게 나옴(실데이터).
  - `GET /api/v1/meals?date=20260805`(오늘, 방학 기간) — `200 OK` + `meals: []`로 정상 처리,
    에러로 취급되지 않음을 확인.
  - `GET /api/v1/meals?date=20260810&mealType=LUNCH` — 중식만 걸러져 반환됨을 확인.
  - `GET /api/v1/timetables?date=20260323`(Authorization 헤더 없이 호출) — `401` +
    `{"code":"COMMON_002"}`로 인증 미들웨어가 정상 차단함을 확인.
- 검증에 쓴 서버는 종료 후 포트(9091) 점유 프로세스까지 확인해서 정리했다.

### 검증하지 못한 것
- **로그인 상태의 `GET /api/v1/timetables` 실제 성공 응답**(3학년 학과 매핑 포함)은 로컬
  DB에 학과가 갈린 3학년 테스트 계정을 새로 만들어야 하는데, 정상 가입 흐름이 휴대폰 인증
  (SMS)을 필요로 해 이번 QA에서는 실제 서버로 재현하지 않았다. 대신 `TimetableServiceTest`에서
  `UserRepository`/`Gbsw`를 모킹해 1·2학년 분기, 3학년 학과 매핑(4가지 반 전부), 교시 정렬,
  선생님 계정 예외까지 전부 단위 테스트로 커버했다 — NEIS 호출 파라미터(`DDDEP_NM`/
  `CLASS_NM`)가 학과 매핑표대로 정확히 구성되는지까지 `argThat`으로 검증함.

## 코드 리뷰 (자체 점검)

### 확인한 항목 (문제 없음)
- API 키(`615b26b0...`)는 로컬 `application-dev.yml`(git 미포함)에만 있고, 기획서/코드/커밋
  어디에도 평문으로 남기지 않았음을 `grep`으로 재확인.
- `Gbsw.classNo`(학교 전체 기준 1~4반) 의미를 바꾸지 않았음 — `AuthService`의 기본 닉네임
  생성 로직(`grade+classNo+number+이름`)에 영향 없음(diff 재확인, 관련 코드 미수정).
- `/api/v1/timetables`만 인증 요구로 전환했고 `/api/v1/meals`는 기존처럼 비인증 유지
  (`SecurityConfig` diff 확인).
- NEIS 응답 파싱(`NeisClient`)이 3가지 형태를 전부 구분하고, `meal`/`timetable` 서비스는 이
  복잡함을 몰라도 되게 캡슐화됨.

### 발견한 사항 (이번 이슈 범위 밖, 참고용)
- `MealControllerTest`에서 `date` 쿼리 파라미터에 날짜로 파싱할 수 없는 값(`not-a-date`)을
  주면 `MethodArgumentTypeMismatchException`이 발생하는데, `GlobalExceptionHandler`의
  `Exception` 폴백이 이를 잡아 `500`으로 응답한다 — 원래는 `400 Bad Request`가 맞는 상황이다.
  이건 `#26`에서 새로 만든 문제가 아니라, 쿼리 파라미터에 타입 바인딩을 쓰는 기존 엔드포인트
  전체에 이미 있던 동작이라 이번 범위에서 고치지 않았다. 필요하면 별도 이슈로 다룰 것을 제안.

## 요약
- NEIS 급식/시간표 연동(`neis`/`meal`/`timetable` 3개 패키지) 구현 완료 — 자동 테스트 +
  라이브 호출 둘 다 검증
- 시간표는 Access Token 기반 본인 학급 자동 조회로 구현, 3학년 학과 매핑은 하드코딩 후 이슈
  #27로 재검증 시점 추적
- 발견한 기존 이슈(쿼리 파라미터 타입 불일치 시 500 응답)는 범위 밖으로 문서화만 하고 넘어감
