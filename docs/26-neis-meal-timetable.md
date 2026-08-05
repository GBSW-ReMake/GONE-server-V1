# #26 나이스(NEIS) API 연동 — 급식 정보 + 학년/반별 시간표 조회

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/26

## 개요/목적
프론트에 보여줄 급식(조/중/석식) 정보와 학년/반별 시간표를, 경북소프트웨어마이스터고등학교를
대상으로 나이스(NEIS) 오픈 API에서 가져와 우리 서버가 프록시 형태로 내려준다. 프론트는 NEIS
인증키를 몰라도 되고, 우리 서버가 NEIS 호출을 대신하고 응답을 우리 API 스펙으로 정리해서
돌려준다.

## NEIS 오픈 API 명세 (docs 폴더의 xls 원본 + 실제 호출로 검증 완료)

### 급식식단정보 — `GET https://open.neis.go.kr/hub/mealServiceDietInfo`
**요청 인자**
- `KEY` (필수) — 인증키
- `Type` (필수) — `json` 고정 사용
- `pIndex` (필수) — 페이지 위치
- `pSize` (필수) — 페이지당 건수
- `ATPT_OFCDC_SC_CODE` (필수) — 시도교육청코드. 우리 학교 고정값 `R10`(서버 설정으로 고정,
  클라이언트가 안 보냄)
- `SD_SCHUL_CODE` (필수) — 행정표준코드. 우리 학교 고정값 `8750829`(위와 동일)
- `MMEAL_SC_CODE` (선택) — 식사코드(1=조식, 2=중식, 3=석식)
- `MLSV_YMD` (선택) — 급식일자(`yyyyMMdd`)

**출력 필드(우리가 쓰는 것만)**: `MMEAL_SC_NM`(식사명), `MLSV_YMD`(급식일자), `DDISH_NM`(요리명,
`<br/>`로 구분된 여러 줄 문자열), `CAL_INFO`(칼로리)

### 고등학교시간표 — `GET https://open.neis.go.kr/hub/hisTimetable`
**요청 인자**
- `KEY` / `Type` / `pIndex` / `pSize` (필수) — 위 급식 API와 동일
- `ATPT_OFCDC_SC_CODE` / `SD_SCHUL_CODE` (필수) — 위와 동일(서버 고정값)
- `GRADE` (명세상 선택, 우리는 필수로 씀) — 학년
- `CLASS_NM` (명세상 선택, 우리는 필수로 씀) — 학급명(반)
- `ALL_TI_YMD` (선택) — 시간표일자(`yyyyMMdd`)

**출력 필드(우리가 쓰는 것만)**: `ALL_TI_YMD`(일자), `GRADE`, `CLASS_NM`, `PERIO`(교시),
`ITRT_CNTNT`(수업내용=과목명)

### 실제 응답 형태 (실제 호출로 확인 — 세 가지 형태를 전부 다르게 처리해야 함)
1. **정상(데이터 있음)**: 최상위 키가 API마다 다름(`mealServiceDietInfo`/`hisTimetable`) —
   `[{head: [{list_total_count}, {RESULT: {CODE: "INFO-000", ...}}]}, {row: [...]}]` 형태.
2. **정상(데이터 없음)**: 최상위 키가 아예 없고 `{"RESULT": {"CODE": "INFO-200", "MESSAGE":
   "해당하는 데이터가 없습니다."}}`만 온다 — **에러가 아니라 정상 상태**(주말/방학/공강 등).
3. **진짜 에러**(인증키 오류 등): `{"RESULT": {"CODE": "ERROR-290", "MESSAGE": "..."}}` —
   `ERROR-` 접두사로 구분된다. xls 명세서엔 코드가 숫자만(`290`, `300` 등) 적혀 있지만 실제
   응답엔 `ERROR-`/`INFO-` 접두사가 붙는다.

## 엔드포인트

### `GET /api/v1/meals`
- 요청: `date`(쿼리, 선택, `yyyyMMdd`, 기본값 오늘(KST)), `mealType`(쿼리, 선택,
  `BREAKFAST`/`LUNCH`/`DINNER` — NEIS의 `MMEAL_SC_CODE` 1/2/3에 대응, 생략 시 그날 전체)
- 응답: `MealsResponse(date: String, meals: List<MealResponse>)`,
  `MealResponse(mealType: String, dishes: List<String>, calorie: String)`
  - `dishes`는 `DDISH_NM`을 `<br/>` 기준으로 분리한 리스트(알레르기 코드 `(1.2.5...)`는 별도
    파싱 없이 요리명 문자열에 그대로 남겨둠 — 아래 리스크 참고)
- 데이터 없음(주말/방학 등): `200 OK` + `meals: []` (에러 아님)
- NEIS 쪽 진짜 에러/네트워크 실패: `502 Bad Gateway` + `NeisErrorCode.EXTERNAL_API_ERROR`(신규,
  아래 참고)
- 인증/권한 요구사항: 없음(비인증, 공개 정보)

### `GET /api/v1/timetables`
- 요청: `date`(쿼리, 선택, `yyyyMMdd`, 기본값 오늘(KST))만 받는다. `grade`/`classNm`은
  클라이언트가 보내지 않는다 — **Access Token의 `userId`로 로그인 계정을 특정하고, 그 계정의
  `Gbsw`(학교 명단) 레코드에 이미 저장된 `grade`/`classNo`를 서버가 조회해서 사용한다.**
  (아래 "본인 학급 자동 조회" 참고)
- 응답: `TimetableResponse(date: String, grade: int, classNm: String, periods:
  List<PeriodResponse>)`, `PeriodResponse(period: int, subject: String)`
- 데이터 없음(공강/방학 등): `200 OK` + `periods: []`
- NEIS 쪽 진짜 에러/네트워크 실패: `502 Bad Gateway` + `NeisErrorCode.EXTERNAL_API_ERROR`
- 선생님 계정으로 호출(`Gbsw.grade`가 `null`): `400 Bad Request` +
  `GbswErrorCode.NO_CLASS_ASSIGNED`(신규, `GBSW_002`)
- 인증/권한 요구사항: **Access Token 필요**(`SecurityConfig`에
  `.requestMatchers("/api/v1/timetables/**").authenticated()` 추가). 급식(`/api/v1/meals`)은
  학교 공통 정보라 그대로 비인증 유지.

### 본인 학급 자동 조회 (userId → Gbsw.grade/classNo)
- 클라이언트가 학년/반을 알 필요도, 보낼 필요도 없게 한다 — 로그인 상태만으로 본인 시간표가
  나온다.
- 흐름: `JwtAuthenticationFilter`가 Access Token의 `userId` 클레임만으로(별도 DB 조회 없이)
  `UserPrincipal(userId)`를 `SecurityContext`에 심어준다(기존 방식 그대로, 변경 없음) →
  `TimetableController`가 `@AuthenticationPrincipal UserPrincipal principal`로 받음 →
  `TimetableService`가 `principal.userId()`로 `User`를 조회하고 `user.getGbsw()`(LAZY, 연관
  엔티티 1회 추가 조회)에서 학년/반 정보를 꺼내 NEIS 조회 로직에 넘김(구체적인 필드는 바로
  아래 "학과 분리 문제" 참고).
- **학년/반을 Access Token 클레임에 직접 넣지 않는 이유**: 토큰은 만료 전까지(현재
  30분) 재검증 없이 신뢰되는데, 진급/반편성처럼 바뀔 수 있는 값을 토큰에 박아두면 그 사이
  낡은 값을 계속 쓰게 된다. `userId`만 토큰에 두고 나머지는 매 요청 DB에서 최신값을 읽는 현재
  `UserPrincipal` 설계 원칙(주석: "추후 이름/권한 등이 필요해지면 그때 확장")을 그대로 따른다.
- 요청마다 DB 조회가 1회 늘지만(`User` PK 조회 + `Gbsw` LAZY 로딩), NEIS 응답 자체가 6시간
  캐싱되므로 부담은 미미하다.
- 선생님 계정(`Gbsw.type == TEACHER`)은 학년/반 정보가 없어 이 엔드포인트를 쓸 수 없다 —
  `GbswErrorCode.NO_CLASS_ASSIGNED`로 명확히 400 응답한다(500/NPE 방지).

### 학과 분리 문제 (3학년부터 `class_no`만으로는 부족함 — 실제 라이브 조회로 검증 완료)
> 이 절은 사용자가 학교 실제 운영 방식을 알려주고, 학년별로 직접 라이브 조회해서 함께 검증한
> 내용이다.

- 경북소프트웨어마이스터고는 3학년부터 학과(소프트웨어개발과 2개 반, 인공지능소프트웨어과
  1개 반, 게임개발과 1개 반 = 총 4개 반)로 나뉜다. 학생들이 편하게 부르는 "1반/2반/3반/4반"은
  이 4개 반을 순서대로 부르는 비공식 호칭이고(소개, 소개, 인공, 게임 순), 교실 문 앞
  표지판에는 "3-1", "3-2"처럼 **학과 내부 기준 반 번호**가 붙어 있다.
- NEIS API도 표지판과 같은 기준을 쓴다 — `GRADE=3`으로 학과 필터 없이 조회하면 `DDDEP_NM`이
  세 학과로 갈리고, `CLASS_NM`은 학과마다 다시 1부터 매겨진다(게임개발과=1, 소프트웨어개발과=
  1·2, 인공지능소프트웨어과=1). 즉 `GRADE=3&CLASS_NM=1`만 보내면 세 학과의 1반이 전부 섞여서
  온다 — `DDDEP_NM`까지 같이 보내야 정확한 반 하나로 좁혀진다. (실제 라이브 응답으로 검증:
  `GRADE=3` 전체 조회 시 28건 = 4개 반 × 7교시, 학과별 분리 확인 완료.)
- **1·2학년은 다르다** — 학년별 전체 조회(`GRADE=1`, `GRADE=2`, 반 필터 없이)로 직접 검증한
  결과, 두 학년 모두 `DDDEP_NM`이 4개 반 전부 `소프트웨어개발과`로 통일되어 있고(NEIS 행정
  태그가 아직 학과별로 안 갈림), `CLASS_NM`은 1~4로 학년 전체에서 이미 고유하다. 즉 1·2학년은
  `DDDEP_NM` 없이 `CLASS_NM`(=기존 `class_no`)만으로 정확한 반을 조회할 수 있다.
  - 단, 2학년은 `DDDEP_NM` 태그만 통일돼 있을 뿐 실제 커리큘럼은 이미 갈려 있다(1·2반=서버
    프로그램 구현, 3반=인공지능 모델 학습, 4반=게임엔진 응용 프로그래밍) — NEIS 조회 자체엔
    영향 없지만, 학과 배정이 2학년부터 사실상 확정된다는 뜻이라 아래 데이터 준비 방침에
    반영한다.

**결론 — DB 스키마는 건드리지 않고, 3학년 전용 매핑을 코드에 하드코딩한다.**
`Gbsw`에 학과 컬럼을 추가하고 명단 엑셀을 새로 받는 방안도 검토했으나, 학과 분리가 현재는
"3학년 이 한 세대"에 국한된 특성이라 판단해 더 가벼운 쪽으로 결정했다(엑셀 재작업/스키마
변경 없이 바로 구현 가능).

**매핑 (`class_no` → NEIS 조회값)** — 학교에서 부르는 1~4반 순서(소개, 소개, 인공, 게임)
그대로:
- `class_no=1` → `DDDEP_NM="소프트웨어개발과"`, `CLASS_NM=1`
- `class_no=2` → `DDDEP_NM="소프트웨어개발과"`, `CLASS_NM=2`
- `class_no=3` → `DDDEP_NM="인공지능소프트웨어과"`, `CLASS_NM=1`
- `class_no=4` → `DDDEP_NM="게임개발과"`, `CLASS_NM=1`

**조회 시 분기 로직**: `grade == 3`이면 위 매핑을 거쳐 `DDDEP_NM`+`CLASS_NM`까지 채워서
조회. `grade`가 1·2(또는 그 외)면 매핑 없이 `GRADE`+`CLASS_NM(class_no)`만으로 조회(학과
필터 생략) — 지금까지 검증한 대로 1·2학년은 이걸로 충분하다.

**이 하드코딩이 깨지는 조건 (다음 시즌 작업 시 반드시 재확인 필요)**:
- 지금 3학년이 졸업하고 다음 3학년(현재 2학년)이 올라올 때 — 다행히 2학년 커리큘럼도 이미
  같은 순서(1·2반=소프트웨어, 3반=인공지능, 4반=게임)로 갈려 있는 걸 라이브로 확인했으므로,
  최소 내년까지는 이 매핑이 그대로 유지될 가능성이 높다. 다만 그 다음(현재 1학년 세대부터)은
  아직 학과 배정 자체가 안 됐어서(2학년때 자율 선택) 확실치 않다 — 매년 3월 새 학기 시작 시
  `GRADE=3` 라이브 조회로 `DDDEP_NM`/`CLASS_NM` 분포가 이 매핑과 같은지 다시 확인해야 한다.
- 학과별 반 개수가 바뀌면(예: 인공지능 지원자가 늘어 2개 반이 되면) 매핑 자체를 다시 짜야
  한다.
- 이 매핑은 `TimetableService`(또는 별도 작은 상수 클래스)에 학과 분리 근거 주석과 함께
  하드코딩한다 — DB 마이그레이션 불필요.
- 재검증/갱신 필요 시점을 놓치지 않도록 별도 이슈로 추적: #27

## 요청/응답 목데이터 (실제 NEIS 라이브 호출 결과 기반)
> 아래 예시는 이 이슈 작업 중 실제 라이브 호출로 받은 진짜 데이터다. 오늘(2026-08-05)은
> 방학 기간이라 급식/시간표 둘 다 데이터가 없어서, 데이터가 있는 가장 가까운 날짜로 대신
> 확인했다. 프론트가 실제로 보내는 요청과 받는 응답을 한 쌍으로 묶어 각 케이스마다 명시한다.

### 1) 급식 — 데이터 있는 날
**요청**
```http
GET /api/v1/meals?date=20260810 HTTP/1.1
```
(인증 불필요 — `Authorization` 헤더 없이 호출)

**응답** `200 OK`
```json
{
  "success": true,
  "data": {
    "date": "20260810",
    "meals": [
      {
        "mealType": "조식",
        "dishes": [
          "흑미밥",
          "딸기잼파이(소마) (1.2.5.6)",
          "얼큰돈육감자국 (5.6.10)",
          "두부엿장조림 (5.6.13)",
          "콩나물무침 (5)",
          "치킨너겟&소스 (1.2.5.6.12.13.15)",
          "배추김치 (9)"
        ],
        "calorie": "926.1 Kcal"
      },
      {
        "mealType": "중식",
        "dishes": [
          "아욱된장국 (5.6)",
          "꽈리고추진미조림 (5.6.13.17)",
          "돼지고기수육 (5.6.10)",
          "양파초절이 (5.6.13)",
          "궁채장아찌",
          "모듬파전 (1.5.6.9.17)",
          "배추김치 (9)",
          "사과즙음료(의중) (13)",
          "모듬야채쌈 (5.6.13)"
        ],
        "calorie": "653.5 Kcal"
      },
      {
        "mealType": "석식",
        "dishes": [
          "참치마요구운주먹밥 (1.2.5.6.10)",
          "베트남쌀국수(의중) (5.6.13.15.16.18)",
          "돌나물미나리겉절이 (5.6.13)",
          "무농약레몬단무지",
          "새우만두(소마) (1.5.6.9.10.15.16.17.18)",
          "자두에이드"
        ],
        "calorie": "1198.3 Kcal"
      }
    ]
  },
  "message": "급식 정보를 조회했습니다.",
  "code": null
}
```

### 2) 급식 — 데이터 없는 날(방학/주말, 에러 아님)
**요청**
```http
GET /api/v1/meals?date=20260805 HTTP/1.1
```

**응답** `200 OK`
```json
{
  "success": true,
  "data": { "date": "20260805", "meals": [] },
  "message": "급식 정보를 조회했습니다.",
  "code": null
}
```

### 3) 시간표 — 본인 학급 자동 조회(데이터 있음)
**요청**
```http
GET /api/v1/timetables?date=20260323 HTTP/1.1
Authorization: Bearer {accessToken}
```
(이 토큰의 `userId`가 가리키는 계정의 `Gbsw.grade=3`, `Gbsw.classNo=1`인 경우 — `grade`/
`classNm`은 요청에 없다. 클라이언트는 몰라도 되고 보낼 필요도 없다)

**응답** `200 OK`
```json
{
  "success": true,
  "data": {
    "date": "20260323",
    "grade": 3,
    "classNm": "1",
    "periods": [
      { "period": 1, "subject": "자율활동" },
      { "period": 2, "subject": "정보 통신" },
      { "period": 3, "subject": "* 요구사항 확인" }
    ]
  },
  "message": "시간표를 조회했습니다.",
  "code": null
}
```
> `subject`에 붙는 `*` 접두사는 NEIS `ITRT_CNTNT` 원본 값 그대로다(실제 라이브 호출로 확인 —
> 과목/활동 종류를 나타내는 학교 자체 표기로 추정되나 의미가 명확하지 않아 임의로 제거하지
> 않고 원문 그대로 내려준다. 프론트에서 벗겨내고 싶으면 그건 프론트 표시 로직에서 처리).

### 4) 시간표 — 선생님 계정으로 호출(학급 정보 없음)
**요청**
```http
GET /api/v1/timetables HTTP/1.1
Authorization: Bearer {accessToken}
```
(이 토큰의 `userId`가 가리키는 계정의 `Gbsw.type=TEACHER`, `grade=null`인 경우)

**응답** `400 Bad Request`
```json
{
  "success": false,
  "data": null,
  "message": "학급 정보가 없는 계정입니다.",
  "code": "GBSW_002"
}
```

### 5) 급식/시간표 공통 — NEIS 쪽 진짜 에러(인증키 문제, 네트워크 실패 등)
**응답** `502 Bad Gateway`
```json
{
  "success": false,
  "data": null,
  "message": "외부 학교 정보 서비스와 통신 중 문제가 발생했습니다.",
  "code": "NEIS_001"
}
```

## 데이터 모델 변경
- NEIS 급식/시간표 데이터 자체는 그때그때 가져와 응답으로만 내려주고 우리 DB에 영구 저장하지
  않는다(캐싱은 Redis, 아래 참고).
- 다만 `Gbsw` 테이블에는 컬럼 2개가 추가된다 — `department`(VARCHAR, nullable),
  `neis_class_no`(INT, nullable). 3학년부터 학과별로 NEIS 반 번호가 다시 매겨지는 문제 때문에
  필요(자세한 배경은 위 "학과 분리 문제" 참고). Flyway 마이그레이션 신규 1개
  (`V7__add_gbsw_department.sql`) 필요.

## 설계

### 패키지 구조
- `neis` — 외부 연동 전용(다른 도메인과 무관): `NeisProperties`(API 키/학교 코드 설정값),
  `NeisClient`(실제 HTTP 호출 + 3가지 응답 형태 파싱을 캡슐화), `NeisErrorCode`(신규)
- `meal` — `MealController`, `MealService`, `dto/*`, `MealType`
- `timetable` — `TimetableController`, `TimetableService`, `dto/*`
- `meal`/`timetable` 서비스가 `NeisClient`에 의존하는 구조(기존 `FileController`가
  `R2FileService`에 의존하는 것과 같은 결)
- `TimetableService`는 추가로 `UserRepository`에도 의존(`userId` → `User` → `Gbsw`의
  `grade`/`classNo` 조회용). `MealService`는 사용자 정보가 필요 없어 그대로 `NeisClient`만
  의존.

### HTTP 클라이언트
- Spring `RestClient`(Spring Framework 6.1+, 이 프로젝트가 이미 Spring Boot 4.1이라 사용
  가능) 사용 — 이 프로젝트 첫 외부 API 연동이라 새 컨벤션이 됨. `RestTemplate`/`WebClient`
  대신 최신 동기 클라이언트를 쓰는 이유는 이 앱이 전체적으로 동기(WebMVC) 구조이기 때문.
- `NeisConfig`에서 `RestClient` 빈 하나를 `baseUrl("https://open.neis.go.kr/hub")`로 등록
  (기존 `R2Config`가 `S3Client`/`S3Presigner` 빈을 등록하는 것과 같은 패턴).

### 설정값 (`NeisProperties`, `@ConfigurationProperties(prefix = "neis")`)
- `apiKey`, `atptOfcdcScCode`, `sdSchulCode` — 전부 `@NotBlank`. 실제 값은 로컬
  `application-dev.yml`(git 미포함, 기존 `r2`/`jwt` 시크릿과 동일한 보관 방식)에 저장 완료.
- CI(`ci.yml`)에는 `R2_*`/`JWT_*`처럼 더미 값(`NEIS_API_KEY` 등 환경변수)을 추가해야
  `contextLoads()`가 통과한다 — 실제 NEIS 호출은 안 하므로 더미로 충분.

### 캐싱 (Redis, 기존 `RedisRepository`/`RedisKeyType` 재사용)
- NEIS 쪽에 "요청제한횟수: 제한없음"이라고는 되어 있지만, 매 프론트 요청마다 외부 API를
  왕복하면 지연시간도 늘고 NEIS 장애에도 취약해진다. `RedisKeyType`에
  `MEAL_INFO("neis:meal:", 6시간)`, `TIMETABLE("neis:timetable:", 6시간)` 추가.
  - 캐시 키: 급식은 `{date}`, 시간표는 `{grade}:{classNo}:{date}`(이제 `grade`/`classNo`가
    클라이언트 쿼리 파라미터가 아니라 `Gbsw`에서 조회한 값이라는 점만 다르고, 키 형태 자체는
    그대로 — 같은 반 학생들이 같은 캐시를 공유한다)
  - TTL 6시간을 고른 이유: 급식/시간표는 보통 하루 안에 안 바뀌지만, 정정 공지가 아주 드물게
    있을 수 있어 무기한 캐싱은 피하고 하루 안에 최소 몇 번은 최신화되게 함(자정 이후 재조회
    시 자연스럽게 그날 데이터로 갱신).
  - "데이터 없음"(주말/방학)도 빈 리스트로 그대로 캐싱한다 — 안 그러면 방학 내내 매 요청마다
    NEIS를 계속 호출하게 됨.

### 에러 처리 — 신규 `NeisErrorCode`
- `EXTERNAL_API_ERROR`(502, `NEIS_001`) — NEIS가 `ERROR-*` 코드를 반환했거나 네트워크 자체가
  실패한 경우. 실제 NEIS 코드/메시지는 `log.error(...)`로 남긴다(#24에서 만든 로깅 습관을
  그대로 따름).
- "데이터 없음"(`INFO-200`)은 에러로 취급하지 않고 빈 리스트로 정상 응답한다(위 엔드포인트
  섹션 참고).

### 에러 처리 — `GbswErrorCode` 추가분
- `NO_CLASS_ASSIGNED`(400, `GBSW_002`) — `/api/v1/timetables` 요청자의 `Gbsw.grade`가
  `null`인 경우(선생님 계정). 기존 `GbswErrorCode`(`gbsw` 패키지, `GBSW_001`이 이미 있음)에
  이어서 추가.

## 영향 받는 기존 코드/테스트
- `.github/workflows/ci.yml` — `build-and-test` job env에 `NEIS_API_KEY`,
  `NEIS_ATPT_OFCDC_SC_CODE`, `NEIS_SD_SCHUL_CODE` 더미값 추가(R2/JWT와 동일 패턴)
- `src/main/resources/application-dev.yml` — 이미 로컬에 `neis:` 섹션 추가 완료(git 미포함)
- `common/redis/RedisKeyType.java` — `MEAL_INFO`, `TIMETABLE` 키 타입 추가
- `common/config/SecurityConfig.java` — `.requestMatchers("/api/v1/timetables/**")`도
  `.authenticated()` 목록에 추가(`/api/v1/users/**`, `/api/v1/files/**`와 같은 줄)
- `gbsw/exception/GbswErrorCode.java` — `NO_CLASS_ASSIGNED`(`GBSW_002`) 추가
- 신규 파일: `neis/config/NeisProperties.java`, `neis/config/NeisConfig.java`,
  `neis/NeisClient.java`, `neis/exception/NeisErrorCode.java`, `meal/**`, `timetable/**`
- 테스트: `MealServiceTest`(신규, `NeisClient` mock)/`TimetableServiceTest`(신규, `NeisClient`
  +`UserRepository` mock, 선생님 계정 400 케이스 포함), `NeisClient` 자체는 실제 파싱 로직
  (정상/빈 데이터/에러 3가지 응답 형태)을 더미 JSON 문자열로 단위 테스트.
  `MealControllerTest`(비인증)/`TimetableControllerTest`(신규, 인증 필요 + 요청 검증)

## 리스크 및 고려사항
- **`DDISH_NM`의 알레르기 코드는 파싱하지 않는다**: `"미니버터크루아상/잼 (1.2.5.6.13)"`처럼
  요리명 뒤에 알레르기 유발 성분 번호가 괄호로 붙어 나온다. 이번 이슈에서는 문자열을 그대로
  두고 `<br/>` 구분만 처리한다 — 번호를 실제 알레르기 항목명으로 변환하는 건 별도 매핑 테이블이
  필요한 추가 작업이라 범위 밖으로 둔다.
- **NEIS 응답 형태가 3가지로 갈리는 것**: 위 "실제 응답 형태" 섹션 참고 — `RESULT`가 최상위에
  있는지, 우리가 요청한 리소스명 키(`mealServiceDietInfo`/`hisTimetable`) 아래 있는지로
  분기해야 한다. `NeisClient`에서 이 분기를 한 곳에 모아두고 `meal`/`timetable` 서비스는 이걸
  몰라도 되게 한다.
- **학교 코드/시도교육청코드를 클라이언트가 안 보내고 서버가 고정**: 지금은 우리 학교 하나만
  다루므로 프론트 쪽 API를 단순하게 유지하려고 이렇게 결정했다. 나중에 여러 학교를 지원해야
  하면(가능성 낮음) 그때 요청 파라미터로 바꾸면 된다.
- **캐시 TTL 6시간은 임의로 정한 값**: 실제 운영해보고 너무 길다/짧다 싶으면 조정 가능 —
  숫자 자체에 특별한 근거는 없음, 리뷰 시 의견 있으면 반영.
- **인증키 노출 방지**: 실제 키 값은 이 문서/코드/커밋 어디에도 적지 않았다 — 로컬
  `application-dev.yml`(git 미포함)에만 있다.
