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

- **`g.number is not null` 조건**: 선생님(TEACHER 타입)은 Gbsw.number가 항상 null이므로
  학번 CONCAT 조건에서 자동으로 배제된다.

## JPQL 포기 — 네이티브 SQL 전환 경위

기획서에 "JPQL `FUNCTION` 방식 1순위, 실패 시 네이티브 쿼리로 전환" 조건이 명시되어 있었고,
아래 두 단계 시도 후 네이티브 SQL로 확정했다.

**1차 시도(JPQL `function('LPAD', g.number, 2, '0')`)**: 기동 시 `FunctionArgumentException`
— Hibernate 7이 `LPAD`의 첫 번째 인자 타입 STRING을 기대하는데 `g.number`(INTEGER)가 전달됐다.

**2차 시도(JPQL `function('LPAD', cast(g.number as string), 2, '0')`)**: 기동 시
`SemanticException: Operand of 'like' is of type 'java.lang.Object'` — `function()` 래퍼는
반환 타입이 항상 `Object`로 추론된다. `cast`로 인자를 STRING으로 바꿔도, 바깥
`function('CONCAT', ...)` 반환 타입이 또 `Object`이어서 LIKE 왼쪽에 쓸 수 없다. 구조적으로
해결 불가.

**확정 — 네이티브 SQL + 2-query 패턴**:

- ID만 네이티브 SQL로 조회(`findIdsByQuery`, `findIdsByQueryAndRoles`)
- 엔티티는 JPQL `join fetch`로 조회(`findAllByIdWithGbsw`) — N+1 방지
- 이 프로젝트에 `SchoolCampSessionRepository`에 이미 `nativeQuery = true` 선례 존재

## QA (10단계)

### 정적 검증

- `./gradlew checkstyleMain checkstyleTest` — **통과**, maxWarnings=0
- `./gradlew test --tests "com.remake.gone.user.*"` — **전부 통과**, 기존 테스트 회귀 없음
- 신규/보강 테스트:
  - `UserServiceTest.Search`: 8건(학생 필드 포함, 선생님 필드 null, 빈 목록, LIKE 이스케이프,
    역할 목록 비었을 때 검증 스킵, 유효 코드 단일/복수, 알 수 없는 코드 400, 혼합 목록 첫
    번째 무효 코드에서 400)
  - `UserControllerTest.Search`: 6건(빈 query 400, 누락 query 400, role 없을 때 빈 목록,
    단일 역할 파싱, 복수 역할 파싱, 공백 trim)

### 실서버 기동 + 실제 쿼리 실행 검증

로컬 MySQL(3307) + Redis(6379) 기동 상태에서 `./gradlew bootRun --args='--spring.profiles.active=dev'`:

- 정상 기동 — 네이티브 SQL 쿼리 두 개 모두 리포지토리 빈 생성 시점에 파싱 오류 없이 통과
- 실계정(`testuser`)으로 로그인 후 토큰 발급 → 각 케이스 curl로 검증

#### Happy path 검증 결과

| 케이스 | 기대 | 결과 |
|--------|------|------|
| `?query=길` (role 없음) | 200, 이름에 '길' 포함 학생 반환, studentNumber 포함 | ✅ studentNumber='1101' 포함 확인 |
| `?query=11` (학번 검색) | 200, 학번에 '11' 포함 학생 반환 | ✅ 1101, 1199 반환 |
| `?query=Q&role=STUDENT` | 200, 학생만 | ✅ studentNumber 있는 결과만 |
| `?query=Q&role=TEACHER` | 200, 교사만(studentNumber=null) | ✅ studentNumber=None만 |
| `?query=Q&role=TEACHER` vs `role 없음` | 교사 포함 여부 차이 | ✅ 역할 필터 실제 분기 확인 |
| `?query=%25` (% URL인코딩) | 200, 빈 배열(이스케이프) | ✅ count=0 |
| `?query=x&role=UNKNOWN_CODE` | 400, COMMON_001 | ✅ |
| `?query=` | 400, COMMON_001 | ✅ |
| 인증 없이 | 401, COMMON_002 | ✅ |
| `?query=Q&role=TEACHER,%20DISCIPLINE` (공백 포함 복수 역할) | 200, 교사/생활부장 역할 모두 포함, trim 정상 동작 | ✅ 교사 결과 반환, studentNumber=null 확인 |
| query 파라미터 자체 누락 | 400, COMMON_001 | ✅ |

**핵심 검증**: role 없는 경우(`findIdsByQuery` 경로)가 실제 DB 쿼리 실행 시에도 정상 동작
확인 — 기동 시 JPQL 파싱만 통과하는 것과 달리, 실제 SELECT 결과가 올바르게 반환됨을 검증.

## 발견된 문제 (심각도별)

모두 수정 완료. 미해결 항목 없음.

## 완료 조건 확인

- [x] `checkstyleMain` / `checkstyleTest` 통과
- [x] `UserServiceTest`, `UserControllerTest` 단위 테스트 전부 통과
- [x] 실서버 기동(네이티브 SQL 파싱 오류 없음) 확인
- [x] Postman happy path 검증 (curl로 전 케이스 실검증 완료)
- [ ] CI 통과 — PR 생성 후 확인 필요
