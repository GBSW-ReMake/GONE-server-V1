# #129 유저 검색 개선 — 코드 리뷰 & QA 결과

기획서: [129-user-search-improve.md](./129-user-search-improve.md)

## 코드 리뷰 (9단계, 별도 에이전트)

구현한 세션이 아니라 새로 띄운 에이전트가 `dev...HEAD` diff와 기획서를 보고 독립적으로
리뷰했다([branch-workflow.md](../../rules/branch-workflow.md) 9단계 규칙 참고).

발견된 문제 1건 즉시 수정, 재빌드/재테스트 확인:

- **역할 코드 공백 버그**: `role=TEACHER, DISCIPLINE`처럼 쉼표 뒤에 공백이 오면 `split(",")` 결과가
  `"TEACHER"`, `" DISCIPLINE"`이 되어 `existsByCode(" DISCIPLINE")`가 항상 실패(400 반환)했다.
  `Arrays.stream(...).map(String::trim)`으로 수정하고 테스트 추가.

리뷰에서 "문제 아님"으로 확인된 항목:

- **JPQL `g.number is not null` 조건**: 선생님(TEACHER 타입)은 Gbsw.number가 항상 null이므로
  학번 CONCAT 조건에서 자동으로 배제된다. `g.type = STUDENT` 추가 조건은 설계 확정된 방식이고,
  데이터 정합성은 엔티티/DB 제약으로 보장됨.
- **통합 테스트 갭**: JPQL 실제 실행 여부는 단위 테스트로 검증 불가(리포지토리를 목킹하므로).
  실서버 기동으로 JPQL 파싱 오류 없음을 확인(아래 QA 절 참고).

## QA (10단계)

### 정적 검증

- `./gradlew checkstyleMain checkstyleTest` — **통과**, maxWarnings=0
- `./gradlew test --tests "com.remake.gone.user.*"` — **전부 통과**, 기존 테스트 회귀 없음
- 신규/보강 테스트:
  - `UserServiceTest.Search`: 8건(학생 필드 포함, 선생님 필드 null, 빈 목록, LIKE 이스케이프,
    역할 목록 비었을 때 검증 스킵, 유효 코드 단일/복수, 알 수 없는 코드 400, 혼합 목록 첫 번째
    무효 코드에서 400)
  - `UserControllerTest.Search`: 5건(빈 query 400, 누락 query 400, role 없을 때 빈 목록,
    단일 역할 파싱, 복수 역할 파싱, 공백 trim)

### 실서버 기동 검증

로컬 MySQL(3306) + Redis(6379) 기동 상태에서 `./gradlew bootRun --args='--spring.profiles.active=dev'`
로 기동:

- 정상 기동 — `@Query` JPQL(`function('LPAD', ...)`, SpEL `:#{#roles.isEmpty()}` 포함)이
  리포지토리 빈 생성 시점에 파싱 오류 없이 통과됨을 확인

#### Happy path 케이스 (Postman으로 검증 필요)

다음 케이스는 Postman 컬렉션을 업데이트한 뒤 실제 토큰으로 검증한다(step 15에서 수행):

| 케이스 | 예상 결과 |
|--------|-----------|
| `?query=김` | 이름에 "김" 포함, 학생은 studentNumber/grade/classNo/number 포함 |
| `?query=31` | 학번에 "31" 포함(3학년 1반 학생) 검색됨 |
| `?query=박&role=TEACHER` | 실명에 "박" 포함 + TEACHER 역할 사용자만 반환 |
| `?query=x&role=UNKNOWN_CODE` | 400 COMMON_001 |
| `?query=x&role=TEACHER, DISCIPLINE` (공백 포함) | trim 후 정상 처리 |
| `?query=%` | 빈 배열(LIKE 이스케이프 동작) |
| `?query=` | 400(빈 검색어) |
| `query` 파라미터 누락 | 400 |

## 발견된 문제 (심각도별)

**Low**

- Postman happy path 검증(인증된 실사용자 대상)이 step 15(컬렉션 업데이트) 전에는 수행되지
  않은 상태. step 15 완료 후 위 케이스 테이블 기준으로 실제 확인 필요.

## 완료 조건 확인

- [x] `checkstyleMain` / `checkstyleTest` 통과
- [x] `UserServiceTest`, `UserControllerTest` 단위 테스트 전부 통과
- [x] 실서버 기동(JPQL 파싱 오류 없음) 확인
- [ ] Postman happy path 검증 — step 15에서 수행
- [ ] CI 통과 — PR 생성 후 확인 필요
