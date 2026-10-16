# #158 본인 역할(Role) 목록 조회 API

## 개요 / 목적

현재 유저가 본인에게 어떤 역할(예: 선도부, 총무부 등 부서 역할)이 부여되어 있는지 조회할 수
있는 API가 없다. `Role`/`UserRole` 데이터 모델과 JWT `roles` 클레임 배관은 이미 있지만
(#11, #15), 클라이언트가 로그인 이후 "내 역할 목록"을 직접 조회할 방법이 없다.

이 문서는 그 조회 API 1개(`GET /api/v1/users/me/roles`)를 설계한다. 새로운 도메인 개념을
추가하는 것이 아니라, 이미 존재하는 `UserRole` 데이터를 클라이언트가 꺼내볼 수 있는 창구를
하나 여는 작업이다.

## 관련 이슈 링크

- 해결 대상: #158
- 선행 이슈(데이터 모델 · JWT 클레임): #11, #15

## 엔드포인트

### `GET /api/v1/users/me/roles`

기존 `/api/v1/users/me/*` 네이밍 패턴(`UserController`)을 그대로 따른다.

- **요청 DTO**: 없음. Path/Query 파라미터 없이 Access Token만으로 본인을 식별한다.
- **응답 DTO**: `UserRolesResponse`

  ```java
  public record UserRolesResponse(List<RoleInfo> roles) {

    public record RoleInfo(String code, String name) {}
  }
  ```

  - `code`: JWT `roles` 클레임과 동일한 값(예: `"DISCIPLINE"`) — 클라이언트 로직 분기용
  - `name`: 화면 표시용 한글명(예: `"선도부"`) — 별도 조회 없이 바로 뱃지/라벨에 사용

  응답 예시:

  ```json
  {
    "success": true,
    "data": { "roles": [{ "code": "STUDENT", "name": "학생" }, { "code": "DISCIPLINE", "name": "선도부" }] },
    "message": "역할 목록 조회에 성공했습니다.",
    "code": null
  }
  ```

- **성공 상태코드**: `200 OK`. 역할이 하나도 없는 계정(가입 직후 등)도 정상 케이스이며,
  이때는 에러가 아니라 `roles: []`인 200 응답을 반환한다.
- **에러 상태코드 / ErrorCode**: 이 엔드포인트에서 새로 정의하는 `ErrorCode`는 없다.
  인증되지 않은 요청(토큰 없음/만료)은 기존 인증 필터 단에서 `401 Unauthorized`로
  이미 처리되므로(`SecurityConfig` 컨벤션 재사용), 컨트롤러/서비스 레이어에서 별도
  예외 케이스를 만들지 않는다.
- **인증/권한 요구사항**: Access Token 필요. 역할 제한 없음 — 로그인한 사용자는 누구나
  본인 데이터만 조회 가능(다른 유저 ID를 파라미터로 받지 않으므로 권한 체크 자체가
  불필요).

## 데이터 모델 변경

없음. 기존 `role`, `user_role` 테이블(V6 마이그레이션)을 그대로 조회만 한다. 신규 Flyway
마이그레이션 불필요.

`UserRoleRepository`에 조회 메서드 1개만 추가한다.

```java
/**
 * 특정 사용자가 가진 역할들을 조회합니다.
 *
 * @param userId 조회할 사용자 ID
 * @return 역할 엔티티 목록 (코드 + 한글명)
 */
@Query("select ur.role from UserRole ur where ur.user.id = :userId")
List<Role> findRolesByUserId(@Param("userId") Long userId);
```

기존 `findRoleCodesByUserId`(코드만 반환, JWT 생성 시 사용 중으로 추정)는 그대로 두고
건드리지 않는다 — 이번 엔드포인트는 화면 표시용 `name`이 추가로 필요해서 별도 메서드를
둔다.

## 영향 받는 기존 코드 / 테스트

- **신규**: `role/dto/UserRolesResponse.java`, `role/service/RoleService.java`,
  `role/controller/RoleController.java`
- **수정**: `role/repository/UserRoleRepository.java`에 메서드 1개 추가
- **미수정**: `Role`, `UserRole` 엔티티, 기존 JWT 발급 로직, `UserController` — 전부
  그대로 둔다
- **테스트**: 역할이 1개 이상 있는 유저, 역할이 0개인 유저, 인증 토큰 없는 요청(401)
  3가지 케이스를 `RoleControllerTest`(또는 `RoleServiceTest`)로 검증

## 리스크 및 고려사항

api-design.md 6원칙 검토:

- **단일 책임**: 본인 역할 조회 하나만 담당. 역할 부여/변경은 이 이슈 범위 밖(추후
  "역할 일괄/개별 관리(관리자)" 기능에서 별도로 다룸).
- **빠른 시작**: 응답 크기가 매우 작고(한 유저당 역할 몇 개 수준) 페이지네이션 불필요.
- **일관성**: `/api/v1/users/me/*` 경로 패턴, `ApiResponse` 래퍼, `@AuthenticationPrincipal
  UserPrincipal` 인증 방식 등 기존 `UserController` 컨벤션을 그대로 재사용한다.
- **의미 있는 오류**: 이 엔드포인트 자체에는 도메인 특화 에러 케이스가 없다(인증
  실패는 공통 필터가 처리). 별도 `ErrorCode` enum을 만들지 않는다.
- **확장성/성능**: 역할 개수가 유저당 소수이므로 N+1이나 성능 이슈 없음. `select ur.role`
  형태의 JPQL로 필요한 컬럼만 한 번에 가져온다.
- **하위 호환성**: 신규 엔드포인트 추가만 있고 기존 API·JWT 클레임 구조 변경 없음.

기타:

- JWT `roles` 클레임(코드만 포함)과 이 API 응답(`code`+`name`)은 데이터 소스는
  같지만 형태가 다르다는 점을 클라이언트 팀에 공유가 필요할 수 있음 — PR 설명에 명시.
- 역할이 0개인 응답을 "정상"으로 볼지 프론트 쪽과 사전 확인 필요(예: 신규 가입 직후
  화면에서 빈 배열을 어떻게 표시할지).
