# #35 탈퇴/졸업 사용자 상태 관리 — 코드 리뷰 결과

관련 기획서: [35-user-deleted-filter.md](./35-user-deleted-filter.md)
형식 규칙: [code-review-template.md](../../rules/code-review-template.md)

## 리뷰 범위/방법

- 대상: `git diff origin/dev...feat/#35-user-status`(`src/` 기준 6개 파일 신규/수정 — 신규
  `UserStatus.java`, 신규 `V10__add_user_status.sql`, 수정 `User.java`/`UserRepository.java`/
  `UserDetailsServiceImpl.java`/`AuthService.java`, 테스트 2개 `AuthServiceTest.java`/
  `UserDetailsServiceImplTest.java`).
- **선행 확인**: 기획서 `docs/domain/user/35-user-deleted-filter.md`는 현재 브랜치
  (`feat/#35-user-status`)와 작업 트리(working tree) 어디에도 없다. `git log --all`로
  추적한 결과 별도 브랜치 `docs/#35-user-deleted-filter`(커밋 `3ca46e0`, `3d61672`)에만
  존재해, 그 브랜치에서 `git show`로 최신본을 읽어 이번 리뷰에 사용했다. `feat/#35-user-status`
  브랜치가 `docs/#35-user-deleted-filter` 브랜치를 병합하거나 리베이스하지 않은 상태로 보이며,
  이 상태 자체는 코드 결함은 아니지만 두 브랜치를 나중에 합칠 때 놓치지 않도록 보스에게
  별도로 확인이 필요하다(코드 리뷰 범위 밖이라 이 문서에는 findings로 올리지 않는다).
- 기획서 "변경 사항" 1~5번 항목(검색 필터, 중복확인 무변경, 로그인 차단, 개별 조회 무변경,
  역할 재사용)을 diff와 하나씩 대조했다 — 항목별 상태:
  1. `searchByRealNameContaining` JPQL에 `u.status = ...UserStatus.ACTIVE` 조건 추가: 일치.
  2. `existsByLoginId`/`existsByName`: diff에 변경 없음, 기획서와 일치.
  3. `UserDetailsServiceImpl.loadUserByUsername`에 `WITHDRAWN` 분기 추가, 기존
     `UsernameNotFoundException`/메시지 재사용, 새 예외 타입 없음: 일치.
  4. `findById`/`findByIdForUpdate`: diff에 변경 없음, 기획서와 일치.
  5. 역할 기반 차단: diff에 `@PreAuthorize` 등 관련 코드 추가 없음, 기획서와 일치("새 코드
     없음"이 그대로 지켜짐).
- 데이터 모델: `V10__add_user_status.sql`이 기획서가 명시한 3가지 변경(`status` 컬럼 추가,
  `deleted_at` → `status_changed_at` 개명, `GRADUATE` role 시드)만 포함하는지 확인 — 일치,
  범위 초과 없음.
- `git grep -n "User.builder()"`, `git grep -n "new User("`을 `src/main` 전체에 돌려
  `User`를 생성하는 지점이 `AuthService.signUp`(`AuthService.java:105`) 한 곳뿐임을
  확인했다 — `status` 컬럼이 `NOT NULL`로 바뀌었는데 놓친 생성 지점이 없는지 확인하는
  체크리스트 7번 항목에 대응.
- `deletedAt`/`deleted_at` 참조를 `src/` 전체에서 검색 — `User.java`의 필드 정의와
  `V2__init_user_and_gbsw.sql`의 컬럼 정의 외에는 참조가 없음을 확인했다(엔티티에서 제거해도
  깨지는 다른 호출부 없음).
- 신규 패키지 `user.enums`의 위치를 `gbsw.enums`/`outing.enums`/`meal.enums`/
  `notification.enums`와 대조 — 기존 컨벤션과 일치, `code-style.md`의 "목록에 없는 폴더" 조항
  해당 없음.
- `./gradlew checkstyleMain checkstyleTest`를 로컬에서 직접 실행 — `BUILD SUCCESSFUL`,
  경고 0건(줄바꿈/줄 길이/import 정렬 포함, 100자 제한 위반 없음).
- `./gradlew test --tests AuthServiceTest --tests UserDetailsServiceImplTest`를 직접
  실행 — 신규/수정 테스트 6건(`AuthServiceTest` 1건, `UserDetailsServiceImplTest` 5건) 전부
  통과.
- `sentence-refinement.md` 원칙 6(코드 주석 전용 규칙) 대조 — 신규/수정 주석 3곳
  (`UserDetailsServiceImpl.java:31-32`의 인라인 주석, `User.java:69`/`74`의 필드 Javadoc,
  `UserRepository.java:75-76`의 메서드 Javadoc 보강)이 전부 "왜"(정보 노출 방지 근거, 상태
  변경 시각의 의미)를 설명하고 있어 위반 없음.
- MySQL 8.0 문법 대조: `ADD COLUMN ... NOT NULL DEFAULT 'ACTIVE'`(기존 행 자동 채움),
  `RENAME COLUMN`(8.0 네이티브 지원) 모두 MySQL 8.0에서 정상 동작하는 문법이고, 기존 행이
  있는 상태에서 `NOT NULL` 컬럼을 추가할 때 `DEFAULT`를 함께 지정해 즉시 채워지므로 안전하다.
  컬럼 추가 → 개명 순서도 서로 독립적인 두 컬럼에 대한 작업이라 순서 무관하게 안전하다.
- JPQL 기반 검색 필터(`u.status = ACTIVE`)와 상태별 로그인 차단은 이 프로젝트에
  `@DataJpaTest`/리포지토리 통합 테스트 인프라가 전혀 없어(코드베이스 전체 확인) 단위
  테스트로 검증할 수 없다 — 기획서 "테스트" 절의 해당 항목(검색 결과 필터링, 재사용 불가
  확인)은 QA 단계에서 실서버로 수동 검증하는 것이 이 프로젝트의 기존 관례와 일치한다. 이
  자체는 findings로 올리지 않았다.

---

## 1. 🟡 Medium — 탈퇴 계정과 계정 미존재의 예외 메시지가 리터럴로 중복돼 있어 향후 수정 시 정보 노출 재발 위험

**문제**: `UserDetailsServiceImpl.java:29`의 `orElseThrow(() -> new UsernameNotFoundException("계정을
찾을 수 없습니다."))`와 `UserDetailsServiceImpl.java:34`의 `WITHDRAWN` 분기
`throw new UsernameNotFoundException("계정을 찾을 수 없습니다.");`가 동일한 한글 문자열
리터럴을 각각 독립적으로 들고 있다. 이 기능의 핵심 보안 요구사항(기획서 "리스크 및
고려사항" 절, `UserDetailsServiceImpl.java:31-32` 주석)은 "계정 미존재"와 "탈퇴 계정"의
응답이 **항상 완전히 동일해야** "이 아이디는 탈퇴 계정이다"라는 정보가 노출되지 않는다는
전제 위에 서 있다. 그런데 두 메시지를 하나의 상수로 묶지 않아서, 예를 들어 향후 누군가
29번째 줄의 메시지만 "존재하지 않는 계정입니다."로 다듬고 34번째 줄은 그대로 두면(또는 그
반대) 컴파일도, 테스트도(현재 두 테스트 `throwsWhenUserNotFound`/`throwsWhenUserWithdrawn`은
`isInstanceOf(UsernameNotFoundException.class)`만 확인하고 메시지 동일성은 검증하지 않는다 —
`UserDetailsServiceImplTest.java:89-90`, `105-106`) 이 회귀를 잡아내지 못한 채 정확히 이
기능이 막으려던 정보 노출이 재발한다.

**해결 방안**:
1. **메시지를 클래스 상수(`private static final String ACCOUNT_NOT_FOUND_MESSAGE = "계정을
   찾을 수 없습니다.";`)로 추출해 두 곳에서 재사용한다** — 코드 몇 줄 추가만으로 "메시지가
   반드시 같아야 한다"는 불변조건을 코드 구조로 강제할 수 있어 비용 대비 효과가 가장 크다.
   단점은 거의 없다(가독성도 오히려 향상됨).
2. **테스트에서 메시지 동일성을 명시적으로 검증한다**
   (`assertThatThrownBy(...).hasMessage("계정을 찾을 수 없습니다.")`를 두 테스트 모두에 추가,
   또는 두 테스트가 같은 상수를 참조하도록 작성) — 상수 추출 없이도 회귀를 잡아낼 수 있지만,
   "왜 두 메시지가 같아야 하는지"를 상수 이름만큼 코드 자체가 설명해주지는 않는다. 1번과
   병행하는 것이 가장 견고하다.

## 2. 🟡 Medium — `User.status`에 `@Builder.Default`가 없어 향후 빌더 호출 지점이 컴파일타임 없이 `NOT NULL` 제약을 어길 수 있음

**문제**: `User.java:69-72`의 `status` 필드는 `nullable = false`(DB `NOT NULL`, 마이그레이션
`V10__add_user_status.sql:3`과 동일)로 선언돼 있지만 `@Builder.Default`가 없다. `@Builder`만
붙은 상태에서 `.status(...)`를 호출하지 않고 `User.builder()...build()`를 실행하면 Lombok은
`status` 필드를 조용히 `null`로 채운 `User` 객체를 컴파일 에러 없이 만들어낸다. 현재는
`AuthService.java:105-112`에서 `.status(UserStatus.ACTIVE)`를 명시적으로 호출하는 유일한
생성 지점이라 실제 결함은 없다(`src/main` 전체를 `User.builder()`/`new User(`로 검색해
확인). 하지만 기획서 자체가 "스쿨캠핑(`#38`)의 팀원/선생님 검색이 이 리포지토리를 그대로
재사용"할 예정이라고 명시하고 있어, 향후 새 `User` 생성 지점(관리자 일괄 등록, 시드 스크립트
등)이 `.status(...)`를 빠뜨리면 `save()` 시점에야 `DataIntegrityViolationException`으로
실패한다 — 컴파일/코드 리뷰 단계에서는 잡히지 않고 런타임(스테이징/운영 환경에서의 첫
실행)까지 넘어간다.

**해결 방안**:
1. **`@Builder.Default private UserStatus status = UserStatus.ACTIVE;`로 바꾼다** — 이후
   생성되는 모든 `User`가 `.status(...)`를 생략해도 안전하게 `ACTIVE`로 채워진다. `Gbsw`
   FK처럼 필수로 매번 명시해야 하는 값이 아니라 "명시하지 않으면 재학 중"이라는 합리적
   기본값이 존재하는 필드라 이 패턴이 자연스럽다. 단, `AuthService`처럼 이미 명시적으로
   `.status(ACTIVE)`를 호출하는 코드는 그대로 둬도 무방하다(중복이지만 해가 없다).
2. **현재 상태를 유지하고, 새 `User` 생성 지점이 추가될 때 코드 리뷰에서 `.status(...)` 누락
   여부를 체크리스트로 확인한다** — 엔티티 변경이 없어 비용은 0에 가깝지만, 사람이 매번
   기억해야 하는 절차라 리뷰어가 바뀌거나 리뷰를 건너뛰면 그대로 재발한다.

## 3. 🟢 Low — 검색 쿼리의 상태 필터가 파라미터 바인딩 대신 JPQL 문자열에 완전한정 클래스명으로 하드코딩돼 있음

**문제**: `UserRepository.java:86`의 `+ "and u.status = com.remake.gone.user.enums.UserStatus.ACTIVE")`는
`UserStatus` enum을 JPQL 리터럴로 문자열 안에 완전한정 이름(FQN)째로 박아 넣는다. 기능상
문제는 없다(Hibernate가 이 문법을 정상 지원하며, `./gradlew checkstyleMain`/애플리케이션
부팅 시점 쿼리 검증에서도 걸리지 않음을 확인했다). 다만 이 프로젝트의 다른 `@Query` 두
곳(`UserRepository.java:70`의 `findByIdForUpdate`, `UserRoleRepository.java:20`의
`findRoleCodesByUserId`)은 전부 `@Param` 파라미터 바인딩만 쓰고 있어 이번 줄이 이 파일
안에서 유일한 리터럴 하드코딩 사례다. 향후 `user.enums` 패키지를 옮기거나 `UserStatus`를
`Status`로 리네이밍하면, 리팩터링 도구(IDE의 "패키지 이동/이름 변경")가 문자열 안의 FQN까지는
자동으로 갱신해 주지 않아 애플리케이션 부팅 시점에야(쿼리 파싱 실패) 발견된다.

**해결 방안**:
1. **`:status` 파라미터 바인딩으로 바꾼다** — 메서드 시그니처를
   `searchByRealNameContaining(@Param("query") String query)`에서 상태 값을 하드코딩
   `UserStatus.ACTIVE`로 두는 대신, 쿼리를 `"and u.status = :status"`로 바꾸고 메서드
   내부에서 `UserStatus.ACTIVE`를 상수로 넘기거나, 이 메서드 자체를 "활성 사용자만 검색"이라는
   현재 의미 그대로 유지하고 싶다면 메서드 안에서 기본 파라미터로 고정해도 된다. IDE
   리팩터링 안전성이 확보되고 다른 두 `@Query`와 스타일이 통일된다. 단점은 없다시피 하다
   (문자열 한 줄만 바뀐다).
2. **현재 상태를 유지한다** — 이 메서드가 "활성 사용자만 검색한다"는 의도를 쿼리 자체에서
   한눈에 드러낸다는 점에서 가독성 손해가 크지 않고, 리네이밍이 실제로 일어날 가능성도 낮다.
   다만 문제가 실제로 터지면(부팅 실패) 원인 파악에 다른 파일보다 시간이 더 걸릴 수 있다.

---

## 요약

Critical/High 없음. Medium 2건 — (1) 탈퇴/미존재 예외 메시지가 리터럴로 중복돼 있어 향후
수정 시 이 기능의 핵심 보안 전제(정보 비노출)가 조용히 깨질 수 있음, (2) `User.status`에
`@Builder.Default`가 없어 향후(특히 기획서가 예고한 `#38` 스쿨캠핑 재사용 시점) 새 `User`
생성 지점이 상태 지정을 빠뜨려도 컴파일타임에 잡히지 않고 런타임 DB 제약 위반으로만 드러남.
Low 1건 — 검색 쿼리의 enum 필터가 파라미터 바인딩이 아니라 JPQL 문자열 안 FQN 리터럴로
하드코딩돼 있어 리팩터링 안전성이 이 파일의 다른 쿼리보다 낮음.

기획서 대비 범위 초과/누락은 없음 — "변경 사항" 1~5번 항목과 데이터 모델 변경(`V10`
마이그레이션)이 diff와 정확히 일치하고, 명시적으로 "변경 없음"이라 못박은 지점
(`existsByLoginId`/`existsByName`, `findById`/`findByIdForUpdate`, 역할 기반 차단)도 실제로
손대지 않았다. 마이그레이션은 MySQL 8.0 문법상 안전하고(`NOT NULL` + `DEFAULT`로 기존 행
자동 채움, `RENAME COLUMN` 네이티브 지원), `checkstyleMain`/`checkstyleTest`/대상 테스트
전부 로컬에서 통과를 직접 확인했다.
