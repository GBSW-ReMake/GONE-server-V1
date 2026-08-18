# 탈퇴/졸업 사용자 상태 관리 — 기능 기획서

> 관련 이슈: [#35 탈퇴/소프트 삭제 사용자가 조회·검색 결과에서 필터링되지 않음](https://github.com/GBSW-ReMake/GONE-server-V1/issues/35)
>
> **갱신(2026-08-18, 재설계)**: 원안(`User.deletedAt` 하나로 "탈퇴"를 전부 통합)을 검토하는
> 과정에서 두 가지가 새로 확정됐다 — (1) 탈퇴/졸업 계정의 아이디·별명은 **영구
> 재사용 불가**, (2) **졸업생은 자퇴/퇴학과 다르게 로그인·앱 열람은 계속 되지만 신청류
> "실제 기능"은 못 쓴다.** 이 두 가지는 `deleted_at` 단일 컬럼 + 엔티티 전역 필터
> (`@SQLRestriction`) 방식과 정면으로 충돌해(아래 "폐기한 설계" 참고), 상태(enum) 기반으로
> 다시 설계했다. 신규 엔드포인트는 없지만 데이터 모델 변경이 생겨 이슈 범위가 원안보다
> 커졌다.

## 개요/목적
`User` 조회 경로 전체가 지금은 탈퇴 여부를 전혀 걸러내지 않는다. 실제 탈퇴 처리 기능 자체도
아직 없어 당장 영향은 없지만, 스쿨캠핑(`#38`)의 팀원/선생님 검색이 이 리포지토리를 그대로
재사용하므로 그 착수 전에 먼저 막아둔다. 이번 기획에서 범위가 "탈퇴만 숨기기"에서 "탈퇴/졸업
상태를 구분해서 각기 다르게 처리하기"로 넓어졌다.

## 정책 (확정)
- **상태는 3가지**: 재학(`ACTIVE`), 졸업(`GRADUATED`), 자퇴/퇴학(`WITHDRAWN`). 자퇴/퇴학은
  사유를 구분하지 않고 하나로 묶는다(기존 결정 유지).
- **졸업(`GRADUATED`)**: 로그인 가능, 조회성 기능(프로필/검색 등) 이용 가능. 신청류 "실제
  기능"(외출 신청, 스쿨캠핑 신청 등)은 이용 불가.
- **자퇴/퇴학(`WITHDRAWN`)**: 로그인 자체가 차단된다 — 계정이 없는 것과 동일하게 취급.
- **아이디(`loginId`)/별명(`name`)은 상태와 무관하게 영구 재사용 불가** — 졸업이든 자퇴/퇴학이든
  한 번 쓴 값은 다른 계정이 다시 쓸 수 없다.
- **검색 결과(`GET /users/search`)에는 `ACTIVE`만 노출** — 졸업생도 더 이상 팀원/담당
  후보가 아니므로 숨긴다(자퇴/퇴학과 동일 취급).
- 상태를 채우는 시점(졸업/자퇴 처리)은 여전히 **관리자가 수동으로 처리**하고, 그 처리
  화면/엔드포인트는 이번 범위가 아니라 별도 ADMIN 관리 페이지 몫이다(기존 결정 유지) — 이
  이슈는 그 화면이 나중에 기댈 데이터 모델(상태 컬럼, 역할)만 미리 준비해둔다.

## 폐기한 설계: 엔티티 전역 필터(`@SQLRestriction`)
1차 설계는 `User` 엔티티에 `@SQLRestriction("deleted_at is null")`을 붙여 `findById` 등
모든 조회 경로에 예외 없이 필터를 적용하는 방식이었다. 하지만 이 "예외 없음"이 정확히
문제였다:
- `existsByLoginId`/`existsByName`도 필터링되어 탈퇴 계정의 값이 "사용 가능"으로
  잘못 나온다 — 재사용 불가 정책과 충돌.
- 졸업생의 로그인 조회(`findFirstByLoginIdOrPhoneNumber`)까지 막혀버려, "졸업생은 로그인
  가능"이라는 요구사항 자체를 구현할 수 없다.
- 전역 필터라 "이 조회는 통과, 저 조회는 차단"처럼 조회별로 다르게 켜고 끌 수 없다
  (Hibernate `@Filter`/`@FilterDef`는 세션 단위로 켜고 끌 수 있지만, 기본값이 꺼짐이라
  "필터 적용을 깜빡한다"는 원래 문제를 그대로 되살린다).

그래서 아래처럼 **꼭 필요한 지점(검색, 로그인)에만 조건을 추가하는 표적 방식**으로
바꿨다. "실제 기능 차단"은 새 필터가 아니라 **이미 있는 역할 기반 `@PreAuthorize`를
그대로 재사용**한다(아래 "실제 기능 차단 — 역할 재사용" 참고) — 이게 오히려 화이트리스트
취지에 더 맞는다: 기본은 열려 있고, 역할이 명시적으로 필요한 곳만 자동으로 막힌다.

## 도메인 모델
### `UserStatus` (enum, 신규)
- `ACTIVE` — 재학(기본값)
- `GRADUATED` — 졸업
- `WITHDRAWN` — 자퇴/퇴학

### `User` 엔티티 변경 (마이그레이션 `V10__add_user_status.sql`, 신규)
- `status` 컬럼 추가 (`VARCHAR(20)`, `NOT NULL`, 기본값 `ACTIVE`)
- 기존 `deleted_at` 컬럼은 `status_changed_at`으로 이름을 바꿔 의미를 맞춘다(상태가
  `ACTIVE`를 벗어난 시각 — 탈퇴든 졸업이든 동일하게 기록). 지금까지 이 컬럼은 실제로 쓰는
  코드가 없어(전부 `NULL`) 이름을 바꿔도 기존 데이터에 영향이 없다.

### `Role` 시드 데이터 변경 (마이그레이션 `V10`에 포함)
- `GRADUATE` 역할 코드 신규 추가(`role` 테이블 `INSERT`, `V6`이 만든 시드 패턴과 동일).
  화면 표시명은 "졸업생".

## 변경 사항 (엔드포인트 없음, 기존 로직 수정만)

### 1. 검색 — `searchByRealNameContaining`(`#32`, `UserRepository`)
- **변경 전**: 상태 무관하게 전부 검색됨.
- **변경 후**: JPQL에 `and u.status = com.remake.gone.user.entity.UserStatus.ACTIVE` 조건
  추가. 졸업/자퇴/퇴학 전부 검색 결과에서 제외.

### 2. 아이디/별명 중복 확인 — `existsByLoginId`/`existsByName`
- **변경 없음.** 상태와 무관하게 항상 전체 테이블을 대상으로 확인한다 — 그래야 탈퇴/졸업
  계정이 썼던 값이 영구히 "사용 중"으로 남아 재사용을 막는다.

### 3. 로그인 — `UserDetailsServiceImpl.loadUserByUsername`(`common/security`)
- **변경 전**: `findFirstByLoginIdOrPhoneNumber`로 계정을 찾으면 상태 확인 없이 바로
  `AuthUserDetails`를 반환.
- **변경 후**: 계정을 찾은 뒤 `status == WITHDRAWN`이면, 계정을 못 찾았을 때와 동일하게
  `UsernameNotFoundException("계정을 찾을 수 없습니다.")`를 던진다(`UserDetailsServiceImpl.java:28`의
  기존 예외를 그대로 재사용 — 새 예외 타입 추가 없음). 이 예외는 `AuthenticationManager`를 거쳐
  `AuthService.login()`의 `catch (AuthenticationException e)`(`AuthService.java:209-212`)에서
  잡히고, 계정 미존재·비밀번호 불일치와 동일하게 `AuthErrorCode.INVALID_CREDENTIALS`
  (`AUTH_007`, 401, "아이디 또는 비밀번호가 일치하지 않습니다")로 응답한다 — 이미 있는
  "아이디 유무를 구분하지 않는다"는 보안 결정(`AuthService.java:210-211` 주석)에 `WITHDRAWN`
  분기를 얹는 것뿐이라 응답 쪽 코드는 변경이 없다. `status == GRADUATED`는 정상 로그인 허용 —
  별도 분기 없이 통과.

### 4. 그 외 조회(`findById`, `findByIdForUpdate` 등)
- **변경 없음.** 개별 레코드를 ID로 조회하는 경로는 상태와 무관하게 계속 조회 가능하게
  둔다 — 예: `outing`의 과거 기록에 담당 선생님으로 남아있는 계정이 나중에 졸업/퇴직해도
  그 기록의 담당자 이름은 계속 정상 표시돼야 한다(감사 가능성). "탈퇴 계정이 새로운
  역할/조회에 다시 등장하지 못하게" 막는 건 아래 5번(역할 기반 차단)의 몫이다.

### 5. 실제 기능 차단 — 역할 재사용 (새 코드 없음)
- 새로운 차단 로직을 추가하지 않는다. 이 프로젝트는 이미 `outing` 신청/승인 등 "실제
  기능" 엔드포인트에 `@PreAuthorize("hasRole('STUDENT')")`류 역할 검사가 걸려 있다
  (`#11` 역할 시스템). **졸업 처리 시 관리자가 그 계정의 `STUDENT`(또는 `TEACHER`) 역할을
  제거하고 `GRADUATE` 역할로 교체하는 것을 전제**로 두면, 기존 역할 검사가 자동으로
  졸업생을 걸러낸다 — 새 코드가 필요 없다.
- 역할 검사가 아예 없는 엔드포인트(`GET /users/me`, `PATCH /users/me/name`,
  `GET /users/search`, NEIS 급식/시간표 조회 등, 전부 `isAuthenticated()`만 요구)는
  졸업생도 계속 이용 가능 — 이게 "로그인·앱 열람은 되지만 실제 기능은 안 된다"는 요구사항과
  정확히 맞아떨어진다.
- **전제 조건(이번 범위 밖)**: 역할 교체(`STUDENT` 제거 + `GRADUATE` 부여) 자체는 졸업 처리
  관리자 화면의 몫이라 이번 이슈에서 구현하지 않는다 — 위 동작은 그 화면이 존재하고
  올바르게 역할을 바꿔준다는 것을 전제로 한다. 지금 당장은 졸업 처리 기능 자체가 없어
  실사용 영향이 없다.

## 데이터 모델 변경
- `V10__add_user_status.sql` (신규): `user.status` 컬럼 추가(`NOT NULL DEFAULT 'ACTIVE'`),
  `user.deleted_at` → `user.status_changed_at` 컬럼명 변경, `role` 테이블에 `GRADUATE` 시드
  행 추가.

## 영향 받는 기존 코드
- `User` 엔티티(`status`/`status_changed_at` 필드), 신규 `UserStatus` enum
- `UserRepository.searchByRealNameContaining` (조건 추가)
- `UserDetailsServiceImpl.loadUserByUsername`(`status == WITHDRAWN` 분기 추가, 응답은
  `AuthService`의 기존 `INVALID_CREDENTIALS` 처리 그대로 재사용)
- 영향 없음(코드 변경 불필요): `existsByLoginId`/`existsByName`/`findById`/
  `findByIdForUpdate`, `outing`/`timetable`의 기존 `findById` 호출부

## 리스크 및 고려사항
- **`GRADUATE` 역할을 실제로 부여/회수하는 관리자 기능이 없는 한, 위 5번(역할 기반 차단)은
  이론상의 설계일 뿐 실제로 발동하지 않는다** — 그 관리자 기능이 나올 때 이 전제(졸업 시
  `STUDENT`→`GRADUATE` 교체)를 그대로 지켜야 한다는 걸 그 이슈 기획서에도 남겨야 한다.
- **로그인 실패 메시지 통일**: `WITHDRAWN` 계정의 로그인 시도는 계정 미존재·비밀번호
  불일치와 함께 전부 `AuthErrorCode.INVALID_CREDENTIALS`(`AUTH_007`, 401, "아이디 또는
  비밀번호가 일치하지 않습니다")로 응답한다 — 셋을 구분해서 응답하면 "이 아이디는 탈퇴
  계정이다"라는 사실이 클라이언트에 노출되므로, 이 프로젝트가 이미 아이디 유무를 구분하지
  않기로 정한 것과 같은 이유로 `WITHDRAWN`도 여기에 합류시킨다(정보 노출 최소화, 위 3번
  참고). 트레이드오프: 사용자가 "왜 로그인이 안 되는지" 문의하면, 응답 메시지만으로는
  "탈퇴 계정"과 "아이디/비밀번호 오기입"을 구분할 수 없으므로 관리자가 DB에서 해당
  `loginId`의 `status` 컬럼을 직접 조회해 확인해야 한다. 의도된 트레이드오프이며, 운영 중
  문의가 잦아지면 재검토한다.
- **하위 호환성**: `deleted_at` → `status_changed_at` 컬럼명 변경은 지금까지 이 컬럼을 읽는
  코드가 전혀 없어(전부 `NULL`) 실제 영향이 없다. 혹시 외부에서(예: 수동 SQL, 관리 도구)
  이 컬럼명을 직접 참조하고 있다면 확인이 필요하다 — 코드베이스 안에서는 참조가 없음을
  확인했다.
- **성능**: `status = 'ACTIVE'` 조건은 단순 컬럼 비교라 인덱스 설계에 큰 영향 없음. 검색
  API(`#32`) 외에는 이 조건이 붙는 곳이 없어 이번 범위에서 별도 인덱스는 불필요.

## 테스트
- 상태별 로그인: `ACTIVE`/`GRADUATED` 로그인 성공, `WITHDRAWN` 로그인 거부(에러 메시지가
  "존재하지 않는 계정"과 동일한지 확인)
- 검색 결과: `GRADUATED`/`WITHDRAWN` 계정이 `searchByRealNameContaining` 결과에서 빠지는지
- 재사용 불가: `GRADUATED`/`WITHDRAWN` 계정의 `loginId`/`name`으로 `existsByLoginId`/
  `existsByName`을 호출하면 여전히 `true`가 나오는지(= 재사용 불가 확인)
- `findById` 등 개별 조회는 상태와 무관하게 계속 값이 나오는지(회귀 확인)
