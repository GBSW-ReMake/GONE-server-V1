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
- NEIS 쪽 진짜 에러/네트워크 실패: `502 Bad Gateway` + `NeisErrorCode.EXTERNAL_API_ERROR`
- 인증/권한 요구사항: 없음(비인증, 공개 정보)

### `GET /api/v1/timetables`
- 요청: `grade`(쿼리, 필수, 학년), `classNm`(쿼리, 필수, 반), `date`(쿼리, 선택, `yyyyMMdd`,
  기본값 오늘(KST))
- 응답: `TimetableResponse(date: String, grade: int, classNm: String, periods:
  List<PeriodResponse>)`, `PeriodResponse(period: int, subject: String)`
- 데이터 없음(공강/방학 등): `200 OK` + `periods: []`
- NEIS 쪽 진짜 에러/네트워크 실패: `502 Bad Gateway` + `NeisErrorCode.EXTERNAL_API_ERROR`
- 인증/권한 요구사항: 없음(비인증)

## 데이터 모델 변경
- 없음. 이번 이슈는 NEIS 데이터를 그때그때 가져와 응답으로만 내려주고 우리 DB에 영구 저장하지
  않는다(캐싱은 Redis, 아래 참고). Flyway 마이그레이션 불필요.

## 설계

### 패키지 구조
- `neis` — 외부 연동 전용(다른 도메인과 무관): `NeisProperties`(API 키/학교 코드 설정값),
  `NeisClient`(실제 HTTP 호출 + 3가지 응답 형태 파싱을 캡슐화)
- `meal` — `MealController`, `MealService`, `dto/*`, `MealType`
- `timetable` — `TimetableController`, `TimetableService`, `dto/*`
- `meal`/`timetable` 서비스가 `NeisClient`에 의존하는 구조 (기존 `FileController`가
  `R2FileService`에 의존하는 것과 같은 결)

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
  - 캐시 키: 급식은 `{date}`, 시간표는 `{grade}:{classNm}:{date}`
  - TTL 6시간을 고른 이유: 급식/시간표는 보통 하루 안에 안 바뀌지만, 정정 공지가 아주 드물게
    있을 수 있어 무기한 캐싱은 피하고 하루 안에 최소 몇 번은 최신화되게 함(자정 이후 재조회
    시 자연스럽게 그날 데이터로 갱신).
  - "데이터 없음"(주말/방학)도 빈 리스트로 그대로 캐싱한다 — 안 그러면 방학 내내 매 요청마다
    NEIS를 계속 호출하게 됨.

### 에러 처리 — 신규 `NeisErrorCode`
- `EXTERNAL_API_ERROR`(502) — NEIS가 `ERROR-*` 코드를 반환했거나 네트워크 자체가 실패한 경우.
  실제 NEIS 코드/메시지는 `log.error(...)`로 남긴다(#24에서 만든 로깅 습관을 그대로 따름).
- "데이터 없음"(`INFO-200`)은 에러로 취급하지 않고 빈 리스트로 정상 응답한다(위 엔드포인트
  섹션 참고).

## 영향 받는 기존 코드/테스트
- `.github/workflows/ci.yml` — `build-and-test` job env에 `NEIS_API_KEY`,
  `NEIS_ATPT_OFCDC_SC_CODE`, `NEIS_SD_SCHUL_CODE` 더미값 추가(R2/JWT와 동일 패턴)
- `src/main/resources/application-dev.yml` — 이미 로컬에 `neis:` 섹션 추가 완료(git 미포함)
- `common/redis/RedisKeyType.java` — `MEAL_INFO`, `TIMETABLE` 키 타입 추가
- 신규 파일: `neis/config/NeisProperties.java`, `neis/config/NeisConfig.java`,
  `neis/NeisClient.java`, `meal/**`, `timetable/**`, `neis/exception/NeisErrorCode.java`
- 테스트: `MealServiceTest`/`TimetableServiceTest`(신규, `NeisClient`를 mock), `NeisClient`
  자체는 실제 파싱 로직(정상/빈 데이터/에러 3가지 응답 형태)을 더미 JSON 문자열로 단위 테스트.
  `MealControllerTest`/`TimetableControllerTest`(신규, 요청 검증)

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
