# #32 실명 검색 API + 본인 프로필 확장 — 코드 리뷰 & QA 결과

기획서: [32-user-search-profile.md](./32-user-search-profile.md)

## 코드 리뷰 (9단계, 별도 에이전트 + `code-review` 스킬)
구현한 세션이 아니라 새로 띄운 에이전트가 `dev...HEAD` diff와 기획서를 보고 독립적으로
리뷰했다([branch-workflow.md](../../rules/branch-workflow.md) 9단계 규칙 참고, 이번에 이 방식으로
처음 적용). 발견된 문제 2건은 모두 즉시 수정하고 재빌드/재테스트로 확인했다.

- **N+1 쿼리**: `searchByRealNameContaining`이 `join`(fetch 아님)이라 `Gbsw`가 지연 로딩된 채
  반환됐다. 검색 결과를 순회하며 `user.getGbsw()`를 호출할 때마다 추가 쿼리가 나가 결과가
  N건이면 쿼리도 N+1번 실행됨 — Mockito 단위 테스트로는 잡히지 않는 종류의 버그(리포지토리를
  통째로 목킹하므로 지연 로딩 자체가 재현되지 않음). `join fetch`로 수정.
- **LIKE 와일드카드 미이스케이프**: 검색어(`query`)를 이스케이프 없이 그대로
  `LIKE '%...%'`에 넣고 있어서, 검색어에 `%`나 `_`를 입력하면 의도와 다르게 와일드카드로
  해석됐다(`%`만 입력해도 전체 사용자 매칭). 바인드 파라미터를 쓰므로 SQL 인젝션 자체는
  아니었음. `UserService`에서 이스케이프 후 쿼리에 `escape '\'` 추가, 이스케이프 로직 검증
  테스트 추가.

리뷰에서 "문제 아님"으로 확인된 항목:
- 소프트 삭제(`User.deletedAt`) 사용자가 검색 결과에 포함되는 것 — 이 필드는 프로젝트 전체
  어디서도 아직 필터링되지 않는 미완성 기능(탈퇴 기능 자체가 없음)이라, 이 PR이 새로 만든
  회귀가 아니라 기존부터 있던 전역 공백으로 판단해 이번 범위에서 다루지 않음.
- `concat('%', :query, '%')`의 SQL 인젝션 가능성 — `:query`는 바인드 파라미터라 안전.

## QA (10단계)
### 정적 검증
- `./gradlew build`(checkstyleMain/checkstyleTest/test 전체 포함) — **통과**
- 신규/보강 테스트: `UserServiceTest`(검색 3건 + 이스케이프 검증 1건 + 확장된 프로필 조회 3건),
  `UserControllerTest`(검색 파라미터 검증 2건 + principal 전달 1건),
  `GlobalExceptionHandlerTest`(필수 파라미터 누락 시 400 검증 1건) — 전부 통과, 기존 테스트
  회귀 없음

### 실제 서버 기동 검증
로컬 MySQL(3306) + Redis(6379)가 떠 있어 `./gradlew bootRun --args='--spring.profiles.active=dev'`
로 세 차례(초기 구현 시점, N+1/이스케이프 수정 이후, 인증된 happy path 검증 시점) 실제 기동까지
확인했다.
- 매번 정상 기동 — `@Query` JPQL(`join fetch ... escape '\'` 포함)이 리포지토리 빈 생성 시점에
  파싱 오류 없이 통과됨을 확인
- 인증 없이 호출:
  - `GET /api/v1/users/me` → `401 COMMON_002` — 정상
  - `GET /api/v1/users/search?query=x` → `401 COMMON_002` — 정상
  - 유효하지 않은 토큰으로도 동일하게 `401` — 정상

### 인증된 실사용자 happy path 검증 (이 환경에 mysql CLI/Docker가 없어 raw JDBC로 우회)
이 환경에 DB 클라이언트나 Docker가 없어 dev DB에 테스트 계정을 만들 수단이 없었는데,
Gradle 캐시에 이미 있는 `mysql-connector-j` jar를 `jshell --class-path`로 물려 JDBC로 직접
접속하는 방식으로 우회했다(코드/설정 변경 없음, 순수 DB 시드용 1회성 스크립트). 테스트
데이터는 검증 직후 전부 삭제했다.
1. `gbsw` 테이블에 학생 1명(`김철수`, 1학년 1반), 선생님 1명(`박영희`), **회원가입은 안 시킬**
   미가입 학생 1명(`김철민`)을 직접 insert(한글 인코딩 문제를 피하려고 코드포인트로 문자열을
   조립 후 UTF-8 hex로 저장값을 재확인함)
2. `POST /auth/phone/send-code` → 콘솔 로그에서 인증번호 확인(`ConsoleSmsSender`, dev 프로필
   전용) → `POST /auth/phone/verify-code` → `POST /auth/signup`으로 김철수/박영희 두 계정만
   실제로 가입시킴(김철민은 의도적으로 미가입 상태로 남김)
3. 발급받은 Access Token으로 실제 호출, 전부 기대한 그대로 응답:
   - `GET /users/me`(김철수) → `{name: "1101김철수", hasProfileImage: false, profileImageUrl:
     null, realName: "김철수", grade: 1, classNo: 1}`
   - `GET /users/me`(박영희) → `{name: "T-756-박영희", ..., realName: "박영희", grade: null,
     classNo: null}` — 선생님 학년/반 null 확인
   - `GET /users/search?query=김철` → **가입된 `김철수`만** 반환, **미가입 `김철민`은 결과에서
     제외** — `User` 기준 조회(가입자만 검색) 설계가 실제로 의도대로 동작함을 확인
   - `GET /users/search?query=박영희` → 선생님 결과, `grade`/`classNo` 모두 `null`
   - `GET /users/search?query=%`(URL 인코딩 없이 리터럴 `%`) → **빈 배열** 반환 — 코드 리뷰에서
     고친 LIKE 와일드카드 이스케이프가 실제로 동작함을 확인(수정 전이었다면 전체 사용자가
     매칭됐을 상황)
   - `GET /users/search?query=없는이름` → 빈 배열
4. 검증 후 테스트 계정(`user`/`user_role`/`gbsw` 각 2~3건)을 모두 삭제, 기동했던 프로세스도
   종료 처리함

## 발견된 문제 (심각도별)

~~**Medium**: 인증된 실사용자 happy path 미검증~~ → 위 "인증된 실사용자 happy path 검증"
절에서 raw JDBC로 테스트 계정을 만들어 실제로 검증 완료. 해소됨.

**Low**
- `User.deletedAt`(소프트 삭제) 사용자가 검색 결과에 그대로 노출된다 — 위 코드 리뷰 절에서
  설명했듯 이 프로젝트에 아직 탈퇴/소프트 삭제 기능 자체가 없어 이번 PR이 새로 만든 문제는
  아니지만, 검색처럼 "여러 명을 한 번에 보여주는" 엔드포인트는 단건 조회보다 이 공백이 더
  눈에 띄기 쉽다. 탈퇴 기능이 실제로 생기는 시점에 `deletedAt IS NULL` 필터를 어디에 걸지
  전체적으로 정리할 필요가 있다. #32 범위 밖이라 백로그 이슈로 분리:
  [#35](https://github.com/GBSW-ReMake/GONE-server-V1/issues/35).
- 전교생 검색이 가능한 구조(기획서에 이미 리스크로 명시, 최소 검색어 길이 제한 등은 이번
  범위에 넣지 않기로 확정)라는 점은 재확인만 하고 넘어간다.

## 완료 조건 확인
- [x] 로컬 빌드/테스트 통과 (`./gradlew build`)
- [ ] CI 통과 — PR 생성 후 확인 필요
