# #29 외출증 신청 API — 코드 리뷰 & QA 결과

기획서: [29-outing-apply.md](./29-outing-apply.md) / 마스터 기획서: [outing-domain.md](./outing-domain.md)

## 코드 리뷰 (자체 점검, 9단계)
- 구현 범위가 기획서(신청 엔드포인트 1개)를 벗어나지 않았는지 확인 — 벗어난 부분 없음.
  다만 구현 도중 발견된 사항 2건을 즉시 기획서에 반영했다(문서-코드 불일치 방지):
  - 호출자 `STUDENT` 역할 검증(`OUTING_012`) — 엔드포인트 헤더에 "권한: STUDENT"라고
    이미 명시돼 있었는데 "구현 로직" 단계 목록에는 빠져 있던 걸 발견해 채워 넣었다.
  - `SecurityConfig`에 `/api/v1/outings/**` → `authenticated()` 추가 — 이게 없으면 위 역할
    검증 이전에 인증조차 강제되지 않아 익명 요청이 통과해버린다.
- 기존 컨벤션 준수: 패키지 구조(`controller/dto/service/exception/entity/repository/enums/
  utils`), 에러 코드 네이밍(`OUTING_NNN`), `ApiResponse`/`CustomException` 패턴 모두 기존
  도메인(`meal`/`timetable`/`auth`)과 동일하게 따름.
- "지금 이 순간"(오늘 날짜/현재 시각)을 서비스가 직접 `LocalDate.now()`로 구하지 않고
  컨트롤러에서 파라미터로 받게 설계 — `TimetableController`/`MealController`가 날짜를
  파라미터로 받는 기존 패턴과 일관되고, 단위 테스트가 실제 시각에 의존하지 않게 만든다.

## QA (10단계)
### 정적 검증
- `./gradlew build` (checkstyleMain/checkstyleTest/test 전체 포함) — **통과**
- `./gradlew test` — 신규 테스트 23건(`OutingServiceTest` 13, `OutingControllerTest` 6,
  `OutingTimeUtilsTest` 4) 전부 통과, 기존 테스트 회귀 없음

### 실제 서버 기동 검증
로컬 MySQL(3306)이 떠 있어 `./gradlew bootRun --args='--spring.profiles.active=dev'`로 실제
기동까지 확인했다.
- `V7__add_outing.sql` 마이그레이션이 실제 MySQL 8.0 스키마에 오류 없이 적용됨
  ("Successfully validated 7 migrations", "Schema `gone` is up to date")
- 앱이 정상 기동(Hibernate 엔티티 매핑, `@Lock` 리포지토리 메서드 포함 전체 빈 로딩 성공)
- 실제 서버에 `POST /api/v1/outings` 호출:
  - Authorization 헤더 없음 → `401 COMMON_002`("인증이 필요합니다") — 정상
  - 유효하지 않은 토큰 → `401 COMMON_002` — 정상 (JWT 필터가 조용히 인증 실패 처리 후
    `authenticated()` 매처가 차단)
- 테스트 후 기동했던 프로세스는 종료 처리함(계속 떠 있지 않음)

## 발견된 문제 (심각도별)

**Medium**
- 인증된 정상 흐름(실제 학생 계정으로 로그인 → 신청 → 응답 확인)까지는 실 서버로 검증하지
  못했다. 이 dev DB에 테스트용 `STUDENT`/`TEACHER` 계정이 있는지 확인할 DB 클라이언트(mysql
  CLI 등)가 이 환경에 없었고, 처음부터 휴대폰 인증 → 회원가입 플로우를 새로 만드는 건 이
  이슈 범위 대비 과한 작업이라 판단해 생략했다. 비즈니스 로직 자체(날짜/시간/역할/겹침
  검증, 코드 재생성)는 Mockito 기반 단위 테스트 13건으로 촘촘히 커버했지만, "실제 DB에 실제
  로그인한 사용자로 진짜 저장까지 되는지"는 미검증 상태다.
- 이 프로젝트에서 비관적 락(`PESSIMISTIC_WRITE`)을 쓰는 첫 사례인데, 실제 동시 요청 상황
  (같은 학생이 동시에 두 번 신청)에서 락이 의도대로 직렬화하는지는 통합 테스트로 검증하지
  못했다 — 이 저장소에 아직 `@DataJpaTest`/Testcontainers 같은 통합 테스트 인프라 자체가
  없다(기존 도메인들도 전부 Mockito 단위 테스트만 있음). 단위 테스트로는 "락 획득 메서드가
  호출됐는지"만 확인 가능하고 실제 DB 락 동작 자체는 검증 범위 밖이다.

**Low**
- ~~커스텀 시간대 상한(21:10)은 `DINNER` 종료 시각과 맞춘 가정값이다~~ → 주인 확인 결과
  커스텀 시간대(외출 신청 가능 시간) 허용 범위를 `08:40~20:30`으로 확정했다(`DINNER` 프리셋
  자체는 18:10~21:10 그대로 유지, 커스텀 상한과 더 이상 연동하지 않음). 코드/테스트/기획서
  전부 반영 완료.
- `OUTING_012`(STUDENT 역할 필요) 에러 코드는 원래 마스터 기획서 리뷰 시점에는 없었고,
  구현 중 "권한: STUDENT" 요구사항을 실제로 코드에 반영하면서 새로 추가했다 — 두 기획서
  모두에 즉시 반영해뒀다(위 "코드 리뷰" 참고).

## 완료 조건 확인
- [x] 로컬 빌드/테스트 통과 (`./gradlew build`)
- [ ] CI 통과 — PR 생성 후 확인 필요
