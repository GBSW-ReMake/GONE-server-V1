# 유저 검색 개선 — 역할 필터 + 학번 검색 기획서 (이슈 #129)

> 이슈 번호 확정 후 파일명 및 본문 `#129` 을 실제 번호로 교체한다.

- **관련 기획서**: `docs/domain/user/32-user-search-profile.md` (원본 검색 기획서, 이번
  작업은 그 위에 기능을 얹는 것)
- **관련 기획서**: `docs/domain/user/35-user-deleted-filter.md` (`status=ACTIVE` 필터 정책,
  그대로 유지)
- **선행 완료**: #32 (검색 기본 구현), #35 (탈퇴/졸업 필터)

---

## 개요/목적

현재 `GET /api/v1/users/search`는 실명 부분 일치만 지원한다. conduct 부여(#94) /
ConductRequest 생성(#122) / 스쿨캠핑 팀원 등록 / 외출증 담당 교사 지정 — 이 네 기능이 전부
이 API 하나로 사람을 찾는다. 그런데 역할 구분이 없어 학생 선택창에 교사가 섞이고, 학번으로는
아예 검색이 되지 않는다.

이번 이슈에서 두 가지를 추가한다.

1. **역할 필터(`role` 파라미터)**: `role=STUDENT`, `role=TEACHER`, `role=TEACHER,ADMIN` 등
   쉼표로 여러 역할을 지정하면 그 역할을 가진 사용자만 반환한다. 생략하면 지금과 동일하게
   전체 사용자를 반환한다(하위 호환).
2. **학번 검색**: 기존 `query` 파라미터를 그대로 두고, 검색 조건을 `(실명 LIKE %query%)
   OR (학번 LIKE %query%)`로 확장한다. `GbswUtils.studentNumber()`와 동일한
   `학년+반+번호(2자리)` 4자리 포맷으로 SQL에서 계산한다.
3. **응답에 `studentNumber`/`number` 추가**: 학생이면 학번 문자열과 반 번호(정수), 교사면
   `null`.

엔드포인트는 하나로 유지한다. 새 검색 엔드포인트를 도메인마다 만들지 않는다.

---

## 확정 정책 (변경 불가)

- 엔드포인트는 `GET /api/v1/users/search` 하나 유지 — 파라미터 확장만.
- 역할 필터 생략 시 기존 동작 그대로 (하위 호환).
- 학번 검색 방식: "숫자면 학번" 분기가 아니라 `(실명 OR 학번)` OR 매칭.
- 학번 포맷: `GbswUtils.studentNumber()`와 동일(`%d%d%02d` — 학년 1자리 + 반 1자리 + 번호
  2자리 0채움). 이 포맷과 SQL 계산 결과가 반드시 일치해야 한다.
- 최소 검색어 길이 제한 없음 (1자부터 허용).
- 서버 페이지네이션 없음.
- `status=ACTIVE` 필터는 기존 정책(#35) 그대로 유지.

---

## 엔드포인트: `GET /api/v1/users/search` (기존 확장)

**권한**: 인증된 사용자 누구나 (기존과 동일)

### 요청 파라미터

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `query` | 필수 | 실명 또는 학번 부분 일치 검색어. LIKE 와일드카드 이스케이프 처리(기존 정책 유지). |
| `role` | 선택 | 쉼표 구분 역할 코드. 생략 시 전체. 예: `STUDENT`, `TEACHER,ADMIN` |

### 요청 예시

```
GET /api/v1/users/search?query=김
GET /api/v1/users/search?query=김&role=TEACHER
GET /api/v1/users/search?query=3218&role=STUDENT
GET /api/v1/users/search?query=홍길&role=TEACHER,ADMIN
```

### 응답 (`200 OK`)

```json
{
  "success": true,
  "data": [
    {
      "userId": 55,
      "nickname": "길동이",
      "realName": "홍길동",
      "studentNumber": "3218",
      "grade": 3,
      "classNo": 2,
      "number": 18
    },
    {
      "userId": 61,
      "nickname": "쌤",
      "realName": "김선생",
      "studentNumber": null,
      "grade": null,
      "classNo": null,
      "number": null
    }
  ],
  "message": "검색 결과입니다.",
  "code": null
}
```

- `studentNumber`: 학생이면 `GbswUtils.studentNumber()` 포맷(예: `"3218"`), 교사이면 `null`.
- `grade`/`classNo`/`number`: 학생이면 값, 교사이면 `null`. `number`는 반에서의 번호(`Gbsw.number`).

### 에러

| 조건 | HTTP | 코드 | 비고 |
|---|---|---|---|
| `query` 파라미터 자체가 없음 | 400 | `COMMON_001` | 기존 동작 유지 |
| `query`가 빈 문자열 | 400 | `COMMON_001` | 기존 동작 유지 |
| `role`에 존재하지 않는 역할 코드 포함 | 400 | `COMMON_001` | `RoleRepository.existsByCode`로 검증 |

---

## 구현 로직

### `UserController.search` 변경

```
@GetMapping("/search")
public ApiResponse<List<UserSearchResponse>> search(
    @RequestParam @NotBlank String query,
    @RequestParam(required = false) String role   // 추가
) { ... }
```

- `role`이 `null`이 아니면 `,`로 분리해 `List<String>` 로 서비스에 전달.
- `role`이 `null`이면 빈 리스트로 전달 → 기존 전체 검색.

### `UserService.search` 변경

```
public List<UserSearchResponse> search(String query, List<String> roles) {
    // role 유효성 검증 — 존재하지 않는 코드이면 400
    if (roles != null) {
        for (String code : roles) {
            if (!roleRepository.existsByCode(code)) {
                throw new CustomException(CommonErrorCode.INVALID_REQUEST);
            }
        }
    }
    String escaped = escapeLikeWildcards(query);
    List<User> users = userRepository.searchByQueryAndRoles(
        escaped, roles == null ? List.of() : roles, UserStatus.ACTIVE);
    return users.stream().map(this::toSearchResponse).toList();
}
```

- `RoleRepository.existsByCode(code)` — 신규 추가(`findByCode`는 없음).

### `UserRepository` 변경

기존 `searchByRealNameContaining`/`searchByRealNameContainingAndStatus`는 그대로 두고(하위
호환, 기존 테스트 통과), 신규 메서드 세 개를 추가한다.

**학번 SQL 계산 방식**: `CONCAT(g.grade, g.class_no, LPAD(g.number, 2, '0'))`

- 이 결과가 `GbswUtils.studentNumber(gbsw)`(`"%d%d%02d".formatted(grade, classNo, number)`)
  과 동일한 문자열이 나와야 한다.
- 실서버 검증 완료: `query="11"` → 1학년 1반 학생(`studentNumber="1101"`) 정상 반환 확인.

**JPQL 불가 — 네이티브 SQL 채택 이유**:

JPQL의 `FUNCTION('LPAD', g.number, 2, '0')` 호출을 시도했으나 Hibernate 7이 `function()`
래퍼 반환 타입을 항상 `Object`로 추론해, `LIKE` 조건 왼쪽에 쓸 수 없다는 `SemanticException`이
발생했다. `cast(g.number as string)`으로 인자 타입을 바꾸면 LPAD는 통과되지만 바깥
`FUNCTION('CONCAT', ...)` 반환 타입이 또 `Object`여서 같은 문제가 반복된다. 이 프로젝트의 다른
검색 쿼리는 JPQL을 유지한다.

**2-query 패턴 (N+1 방지)**:

- `findIdsByQuery(query, status)` — 역할 필터 없음. **네이티브 SQL**로 ID 목록만 반환.
- `findIdsByQueryAndRoles(query, roles, status)` — 역할 필터 있음. **네이티브 SQL**로 ID 목록만 반환. `roles`는 비어 있으면 안 됨.
- `findAllByIdWithGbsw(ids)` — **JPQL `join fetch`** 로 User + Gbsw를 한 번에 조회. N+1 방지.

서비스 호출 흐름:
```
String escaped = escapeLikeWildcards(query);
List<Long> ids = roles.isEmpty()
    ? findIdsByQuery(escaped, "ACTIVE")
    : findIdsByQueryAndRoles(escaped, roles, "ACTIVE");
if (ids.isEmpty()) return List.of();   // findAllByIdWithGbsw 호출 생략
return findAllByIdWithGbsw(ids).stream().map(this::toSearchResponse).toList();
```

네이티브 SQL 쿼리 (역할 필터 없는 경우):
```sql
SELECT u.id FROM user u JOIN gbsw g ON u.gbsw_id = g.id
WHERE u.status = :status
  AND (g.name LIKE CONCAT('%', :query, '%') ESCAPE '\\'
    OR (g.number IS NOT NULL
        AND CONCAT(g.grade, g.class_no, LPAD(g.number, 2, '0'))
            LIKE CONCAT('%', :query, '%') ESCAPE '\\'))
```

역할 필터 있는 경우 위 쿼리에 `AND EXISTS (SELECT 1 FROM user_role ur JOIN role r ON ur.role_id = r.id WHERE ur.user_id = u.id AND r.code IN :roles)` 추가.

### `UserSearchResponse` 변경

`studentNumber` 필드 추가. 기존 필드(`userId`, `nickname`, `realName`, `grade`, `classNo`)는
그대로 유지한다.

```java
public record UserSearchResponse(
    Long userId,
    String nickname,
    String realName,
    String studentNumber,   // 추가. 학생이면 "3218" 형태, 교사이면 null
    Integer grade,
    Integer classNo,
    Integer number          // 추가. 반에서의 번호(Gbsw.number). 교사이면 null
) {}
```

### `UserService.toSearchResponse` 변경

```java
private UserSearchResponse toSearchResponse(User user) {
    Gbsw gbsw = user.getGbsw();
    boolean isStudent = gbsw.getType() == GbswType.STUDENT;
    return new UserSearchResponse(
        user.getId(),
        user.getName(),
        gbsw.getName(),
        isStudent ? GbswUtils.studentNumber(gbsw) : null,  // 추가
        isStudent ? gbsw.getGrade() : null,
        isStudent ? gbsw.getClassNo() : null,
        isStudent ? gbsw.getNumber() : null);              // 추가
}
```

---

## 변경 파일 목록

| 파일 | 변경 내용 |
|---|---|
| `user/dto/UserSearchResponse.java` | `studentNumber`, `number` 필드 추가 |
| `user/controller/UserController.java` | `role` 파라미터 추가 |
| `user/service/UserService.java` | `roles` 파라미터 수용, role 검증, `toSearchResponse` studentNumber/number 추가 |
| `role/repository/RoleRepository.java` | `existsByCode` 추가 |
| `user/repository/UserRepository.java` | `findIdsByQuery`, `findIdsByQueryAndRoles`(네이티브 SQL), `findAllByIdWithGbsw`(JPQL join fetch) 추가 |

**수정하지 않는 파일**: conduct / schoolcamp / outing 도메인 전체, `GlobalExceptionHandler`,
`CommonErrorCode`, `GbswUtils`(재사용만), `UserRoleRepository`(재사용만).

**DB 마이그레이션 없음**: 기존 테이블 조회만 변경.

---

## 테스트 계획 (`UserServiceTest`, `UserControllerTest`)

### `UserServiceTest` — `Search` 중첩 클래스 추가/확장

| 시나리오 | 검증 항목 |
|---|---|
| `role` 없이 이름 검색 | 기존 동작 유지 — 전체 결과 반환, `studentNumber` 포함 |
| `role=STUDENT` 이름 검색 | 학생만 반환 |
| `role=TEACHER` 이름 검색 | 교사만 반환 |
| `role=TEACHER,ADMIN` 이름 검색 | TEACHER 또는 ADMIN 역할 사용자만 반환 |
| 학번으로 검색 (`query="3218"`) | 해당 학번 학생 반환 |
| 학번 부분 검색 (`query="32"`) | 학번에 "32"가 포함된 학생 반환 |
| 이름·학번 동시 매칭 | 이름 일치 + 학번 일치 결과 합산(중복 없이) |
| 교사의 `studentNumber` | `null` |
| 학생의 `studentNumber` | `GbswUtils.studentNumber(gbsw)`와 동일 |
| 기존 이스케이프 처리 | `%` 입력 시 전체 매칭 안 됨 (기존 테스트 회귀 확인) |

### `UserControllerTest`

| 시나리오 | 검증 항목 |
|---|---|
| `role` 파라미터 포함 요청 | 200 응답, 서비스에 올바른 role 목록 전달 |
| `role` 파라미터 생략 요청 | 기존 동작 유지 |

---

## API 설계 6원칙 체크

1. **한 가지를 잘하기**: 기존 검색 API 확장 — 엔드포인트 추가 없이 파라미터만 넓힘. 새 검색
   엔드포인트를 도메인마다 만들지 않는다는 원칙을 `docs/rules/api-design.md`에 한 줄 추가
   제안(아래 부록 참고).
2. **빠른 시작**: 요청 예시 4가지 + 응답 JSON + 에러 표 포함.
3. **일관성**: `UserSearchResponse` 기존 필드 유지, `studentNumber` 추가. 검색 로직은 기존
   이스케이프/`join fetch`/`status=ACTIVE` 모두 유지.
4. **의미 있는 오류**: `query` 누락/빈값 → `COMMON_001`(기존). 알 수 없는 `role` 코드 → `COMMON_001`(`existsByCode` 검증 확정).
5. **확장성/성능**: 페이지네이션 없음 — 학교 규모(200명대)를 감안한 확정 결정, 기획서에
   명시. 역할 필터 EXISTS 서브쿼리 추가로 쿼리 비용 소폭 증가 — 200명 규모에서는 무시할
   수준이나 향후 규모 확대 시 인덱스(`user_role.user_id`, `role.code`) 검토 필요.
6. **하위 호환성**: `role` 파라미터 optional. `studentNumber` 필드 추가(기존 필드는 그대로).
   `role` 생략 시 기존 동작과 동일한 쿼리 경로 유지.

---

## 리스크 및 고려사항

- **`GbswUtils.studentNumber()` 포맷 불일치**: Java의 `"%d%d%02d"` 와 SQL의
  `CONCAT(grade, class_no, LPAD(number, 2, '0'))`가 반드시 동일한 문자열을 생성해야 한다.
  단위 테스트에서 양쪽 결과를 같은 입력으로 비교해 검증한다.
- **JPQL `FUNCTION('LPAD', ...)` 미지원 — 확정**: Hibernate 7이 `function()` 래퍼 반환 타입을
  `Object`로 추론해 `LIKE` 조건에 쓸 수 없다. 두 차례 시도 후 네이티브 SQL로 전환 확정.
  전환 경위는 `129-user-search-improve-QA.md` 참고.
- **`role=ADMIN` 필터 결과 부족**: 현재 `ADMIN`/`DISCIPLINE` 등 추가 역할은 관리자가 수동
  부여하는데, 그 관리자 기능 자체가 없다. `role=ADMIN` 검색이 빈 결과를 반환할 수 있다 —
  검색 기능의 결함이 아니라 role 부여 기능 미구현 때문이다. 기능 자체는 정상 동작한다.
- **서버 페이지네이션 없음**: 학교 규모(200명대)를 감안한 의도적 결정. 향후 규모가 커지면
  재검토.
- **역할 코드 검증 정책 — 확정**: 존재하지 않는 코드이면 `COMMON_001`(400) 반환. `existsByCode`로 구현 완료.

---

## 부록 — `docs/rules/api-design.md` 추가 제안

> 검토 시 같이 확인 필요.

"사람 검색(`/users/search`)은 항상 하나의 엔드포인트로 통일한다. 새 기능에서 사람 찾기가
필요해지면 새 검색 엔드포인트를 만들지 않고, 이 엔드포인트에 파라미터를 넓히는 방향으로
확장한다." — 한 줄 추가 위치: `api-design.md`의 1번("한 가지를 잘하기") 항목 아래.
