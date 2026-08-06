# #32 학생/선생님 실명 검색 API + 본인 프로필 확장

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/32
관련 마스터 기획서: [schoolcamp-domain.md](./schoolcamp-domain.md)의 "팀원/담당 선생님 검색"
절 — 이 문서는 그중 `user` 도메인이 실제로 만들 부분만 좁힌 것.

## 개요/목적
`schoolcamp`/`outing` 두 도메인 모두 "가입된 사람을 실명으로 찾아서 고르는" UI가 필요한데
지금 그럴 API가 없다. 또한 신청 화면에서 "대표 = 나"를 보여주려면 로그인한 본인의
실명/학년/반도 필요한데, 기존 `GET /api/v1/users/me`는 닉네임/프로필사진 여부만 준다.
이 둘을 `user` 도메인에 먼저 만들어서, 이후 `schoolcamp`/`outing` 구현이 바로 쓸 수 있게
한다.

## 엔드포인트

### 1) `GET /api/v1/users/search?query=` (신규)
- **인증/권한**: 인증된 사용자 누구나
- **요청**: `query`(필수, 실명 부분 일치 검색)
- **응답** (`200 OK`)
```json
{
  "success": true,
  "data": [
    { "userId": 55, "nickname": "영희", "realName": "이영희", "grade": 3, "classNo": 2 },
    { "userId": 61, "nickname": "쌤", "realName": "이영수", "grade": null, "classNo": null }
  ],
  "message": "검색 결과입니다.",
  "code": null
}
```
- **구현 로직**
  1. `query`가 비어있으면 `400`
  2. `userRepository.searchByRealNameContaining(query)`(`@Query` JPQL, `findByIdForUpdate`와
     같은 커스텀 쿼리 패턴)로 **`User` 기준** 조회(위 경고 참고 — `Gbsw`가 아니라 `User`에서
     시작해 가입된 사람만 대상으로 함)
  3. 각 결과의 `nickname = user.getName()`, `realName = user.getGbsw().getName()` 채움
  4. `Gbsw.type == STUDENT`면 `grade`/`classNo`를 값으로 채우고, `TEACHER`면 `null`로 응답
     (필드 자체는 항상 응답에 포함 — 이 프로젝트의 다른 DTO들과 동일하게 "필드는 항상 두고
     값만 null" 컨벤션을 따른다. 필드를 통째로 생략하지 않는다)
- **에러**
  - `query`가 비어있음 → `400` `COMMON_001`(신규 도메인 에러 코드 없음 — `AuthController`의
    `@RequestParam @NotBlank` + 클래스 레벨 `@Validated` 패턴을 그대로 따라
    `ConstraintViolationException` → `GlobalExceptionHandler`의 공통
    `CommonErrorCode.INVALID_REQUEST`로 처리됨)

> ⚠️ **Gbsw는 있지만 아직 회원가입을 안 한 사람도 있을 수 있다** — `Gbsw`(학교 명단)와
> `User`(실제 가입 계정)는 1:1이 아니라 `User`가 `Gbsw`를 참조하는 구조라, 아직 가입 안 한
> `Gbsw` 레코드는 대응하는 `User`가 없다. 검색은 **가입된 사람만** 대상으로 해야 하므로
> `Gbsw`가 아니라 **`User`를 기준으로 조회하고 `User.getGbsw()`로 실명을 붙이는 방향**이
> 맞다(`Gbsw`에서 시작하면 미가입자까지 검색 결과에 섞일 위험이 있음). 위 "구현 로직"은
> 이 점을 반영해 `User` 기준으로 다시 표현하면: `userRepository`에서 `Gbsw.name LIKE`
> 조건으로 조인 조회.
>
> ⚠️ **전교생 검색이 가능해지는 셈이라 접근 범위 고민 필요** — 지금은 인증된 사용자 누구나
> 호출 가능하게 열어뒀다. 오남용 우려가 있으면 최소 검색어 길이 제한 등을 고려할 수 있으나
> 이번 범위에서는 넣지 않았다.

### 2) `GET /api/v1/users/me` (기존 확장)
- **인증/권한**: 기존과 동일(`STUDENT`/`TEACHER` 등 본인)
- **응답 변경** — 기존 필드(`name`, `hasProfileImage`) 유지, 아래 필드 추가
```json
{
  "name": "길동이",
  "hasProfileImage": true,
  "profileImageUrl": "https://.../profile/1/abc.jpg?X-Amz-...",
  "realName": "홍길동",
  "grade": 3,
  "classNo": 4
}
```
- **구현 로직**
  1. 기존 `UserService.getMyProfile` 로직 그대로 사용자 조회
  2. `realName = user.getGbsw().getName()`, `grade`/`classNo` = `Gbsw`에서(선생님 계정이면
     `null`)
  3. `profileImageUrl` = `hasProfileImage`가 `true`일 때만 `R2FileService.generateDownloadUrl`
     (기존 메서드 재사용)로 생성, 아니면 `null`
- **에러**: 기존과 동일(변경 없음)

## 데이터 모델 변경
- 없음. 기존 `User`/`Gbsw` 테이블 조회만 사용.

## 영향 받는 기존 코드/테스트
- `MyProfileResponse`(기존 record)에 `profileImageUrl`/`realName`/`grade`/`classNo` 필드
  추가 — 기존 필드는 그대로 두는 하위 호환 확장이라 기존 `MyProfileResponse` 관련 테스트는
  응답 필드 추가분만 보강하면 됨.
- `UserService`가 `R2FileService`에 새로 의존하게 됨.
- `UserController`에 클래스 레벨 `@Validated` 추가(`AuthController`와 동일한 패턴) —
  `@RequestParam @NotBlank String query`가 실제로 검증되려면 필요.
- 신규: `UserController`에 `GET /api/v1/users/search` 추가, `UserSearchResponse` DTO 신규,
  `UserRepository.searchByRealNameContaining`(`@Query` JPQL, `findByIdForUpdate`와 같은
  커스텀 쿼리 패턴) 추가.
- **구현 중 발견해 수정**: `query` 파라미터 자체가 요청에 없으면(값이 빈 문자열이 아니라
  파라미터가 아예 없는 경우) `@NotBlank`가 아니라 Spring MVC의
  `MissingServletRequestParameterException`이 먼저 발생하는데, `GlobalExceptionHandler`가
  이를 별도로 처리하지 않아 `handleException` 폴백으로 떨어져 `500`이 되는 문제를 발견했다.
  `common/exception/GlobalExceptionHandler`에 전용 핸들러를 추가해 `400`
  `CommonErrorCode.INVALID_REQUEST`로 응답하도록 수정 — `user` 도메인 전용이 아니라 공통
  인프라 수정이라, 필수 `@RequestParam`을 쓰는 다른 기존 엔드포인트(`AuthController`의
  아이디/별명 중복 확인)에도 동일하게 적용된다.
- 신규/기존 테스트: `UserServiceTest`(검색, 확장된 프로필 조회), `UserControllerTest`(요청
  검증), `GlobalExceptionHandlerTest`(필수 파라미터 누락 시 400 응답 검증)

## 리스크 및 고려사항
- 검색 대상은 반드시 **가입된 `User`** 기준이어야 한다(위 리스크 참고) — `Gbsw` 기준으로
  잘못 구현하면 미가입자가 검색 결과에 노출되는 버그가 된다.
- 이 엔드포인트가 `schoolcamp`/`outing` 두 도메인 모두의 선행 작업이라, 이 이슈가 먼저
  끝나야 두 도메인 구현을 시작할 수 있다.
