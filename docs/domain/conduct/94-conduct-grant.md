# 상/벌점 부여 — 기능 기획서 (이슈 #94)

## 개요/목적
교사가 사전 정의된 카테고리를 선택해 학생에게 상점 또는 벌점을 부여한다.
부여된 기록은 `ConductRecord` 테이블에 저장되며, 향후 정정·취소·조회 이슈에서 참조한다.

- **관련 마스터 기획서**: [`docs/domain/conduct/1_conduct-domain.md`](./1_conduct-domain.md) — "1. `POST /api/v1/conduct-records`" 절
- **선행 이슈**: #92 (`ConductCategory` 엔티티·V15 마이그레이션·카테고리 목록 API)

---

## 엔드포인트

### `POST /api/v1/conduct-records` — 상/벌점 부여

**권한**: `TEACHER` (`@PreAuthorize("hasRole('TEACHER')")`)

**요청**
```json
{
  "studentUserId": 101,
  "categoryId": 5,
  "detail": "3교시 10분 지각"
}
```
- `detail`은 선택 항목이다. 생략하면 `null`로 저장된다.
- `categoryId`는 `GET /api/v1/conduct-records/categories`로 받은 목록 중 하나다.

**응답** (`201 Created`)
```json
{
  "success": true,
  "data": {
    "id": 501,
    "studentUserId": 101,
    "studentNickname": "길동이",
    "teacherUserId": 42,
    "teacherNickname": "김선생",
    "categoryId": 5,
    "categoryLabel": "지각",
    "type": "DEMERIT",
    "points": -1,
    "detail": "3교시 10분 지각",
    "status": "ACTIVE",
    "createdAt": "2026-08-22T09:15:00"
  },
  "message": "상/벌점이 부여되었습니다.",
  "code": null
}
```

**구현 로직**
1. `categoryId`로 `ConductCategory` 조회한다. 없거나 `active = false`이면 `400` `CONDUCT_004`를 반환한다.
2. `studentUserId`로 `User`를 조회한다. 없으면 `404` `CONDUCT_005`를 반환한다.
3. `userRoleRepository.findRoleCodesByUserId(studentUserId)`로 역할 목록을 조회한다. `STUDENT` 코드가 없으면 `400` `CONDUCT_006`을 반환한다.
4. `category.getType()`과 `category.getPoints()`를 부여 시점 값으로 스냅샷한다.
5. `ConductRecord`를 저장한다(`status = ACTIVE`, `teacherUserId`는 `@AuthenticationPrincipal UserPrincipal`에서 추출).
6. 저장된 엔티티를 응답 DTO로 변환한다(`studentNickname = student.getName()`, `teacherNickname = teacher.getName()`, `categoryLabel = category.getLabel()`).

> `teacherNickname`은 교사의 `User.name`(별명)을 그대로 사용한다. 마스터 기획서의 원본 표현(`teacher.getGbsw().getName()`)은 실명이지만, 이 프로젝트에서 응답에 노출하는 이름은 별명(`User.name`) 기준으로 통일한다(`UserSearchResponse`, `OutingResponse` 등 기존 패턴 참고).

**에러 케이스**

| 조건 | 상태 코드 | 에러 코드 |
|---|---|---|
| `categoryId`가 없거나 `active = false` | `400` | `CONDUCT_004` |
| `studentUserId`에 해당하는 `User` 없음 | `404` | `CONDUCT_005` |
| 대상 사용자가 `STUDENT` 역할이 아님 | `400` | `CONDUCT_006` |

---

## 데이터 모델 변경

### 신규 enum: `ConductStatus`
```
ACTIVE   — 유효한 기록 (집계 포함)
CANCELED — 취소됨 (집계 제외, 이력에는 표시)
```
`CANCELED` 전환 로직은 이번 이슈 범위 밖이다(후속 정정/취소 이슈).
엔티티에는 필드를 선언해두되, 부여 시 항상 `ACTIVE`로 초기화한다.

### 신규 엔티티: `ConductRecord` (`conduct_record` 테이블)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT PK AUTO_INCREMENT` | 내부 PK |
| `student_user_id` | `BIGINT FK → user.id` | 대상 학생 |
| `teacher_user_id` | `BIGINT FK → user.id` | 부여 교사 |
| `category_id` | `BIGINT FK → conduct_category.id` | 부여 카테고리 |
| `type` | `VARCHAR(20) NOT NULL` | `ConductType` 스냅샷 |
| `points` | `INT NOT NULL` | 부여 시점 점수 스냅샷 |
| `detail` | `VARCHAR(500) NULL` | 추가 사유 (선택) |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` | `ConductStatus` |
| `canceled_at` | `DATETIME NULL` | 취소 시각 |
| `canceled_by_user_id` | `BIGINT FK → user.id, NULL` | 취소 실행자 |
| `cancel_reason` | `VARCHAR(500) NULL` | 취소 사유 |
| `version` | `INT NOT NULL DEFAULT 0` | 낙관적 락(`@Version`) |
| `created_at` | `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP` | |
| `updated_at` | `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | 정정 시 갱신 |

인덱스:
- `(student_user_id, created_at)` — 학생별 이력 조회
- `(teacher_user_id, created_at)` — 교사별 부여 이력 조회

### V16 마이그레이션
`V16__add_conduct_record.sql` — `conduct_record` 테이블 생성(시드 데이터 없음).

### 신규 에러 코드: `ConductErrorCode`

| 상수 | HTTP | 코드 | 메시지 |
|---|---|---|---|
| `CATEGORY_NOT_FOUND_OR_INACTIVE` | `400` | `CONDUCT_004` | 존재하지 않거나 비활성화된 카테고리입니다. |
| `STUDENT_NOT_FOUND` | `404` | `CONDUCT_005` | 대상 학생을 찾을 수 없습니다. |
| `NOT_STUDENT_ROLE` | `400` | `CONDUCT_006` | 대상 사용자가 학생 역할이 아닙니다. |

> CONDUCT_001(기록 없음)·CONDUCT_002(소유권)·CONDUCT_003(이미 취소)은 후속 정정/취소 이슈에서 추가한다.

---

## 신규 파일 목록

| 파일 | 설명 |
|---|---|
| `conduct/enums/ConductStatus.java` | `ACTIVE` / `CANCELED` |
| `conduct/entity/ConductRecord.java` | JPA 엔티티 |
| `conduct/exception/ConductErrorCode.java` | `CONDUCT_004` ~ `CONDUCT_006` |
| `conduct/dto/ConductGrantRequest.java` | 요청 DTO |
| `conduct/dto/ConductRecordResponse.java` | 응답 DTO |
| `db/migration/V16__add_conduct_record.sql` | 테이블 생성 |

기존 변경 파일:
- `conduct/service/ConductService.java` — `grantConduct()` 메서드 추가
- `conduct/controller/ConductController.java` — `POST /` 핸들러 추가

---

## 테스트 계획

`ConductServiceTest`에 `GrantConduct` 중첩 클래스를 추가한다.

| 시나리오 | 검증 항목 |
|---|---|
| 정상 부여 | `ConductRecord` 저장 호출, 응답 DTO 필드 일치 |
| 비활성 카테고리 | `CONDUCT_004` 예외 발생 |
| 존재하지 않는 학생 | `CONDUCT_005` 예외 발생 |
| 학생 역할 아닌 사용자 | `CONDUCT_006` 예외 발생 |

---

## 리스크 및 고려사항

**API 설계 6원칙 체크**

1. **한 가지를 잘하기**: 부여 단건만 담당. 정정·취소·조회는 별도 이슈.
2. **빠른 시작**: 요청/응답 예시와 에러 케이스 예시 포함.
3. **일관성**: `ApiResponse<T>` 래퍼, `CONDUCT_NNN` 에러 코드 네이밍, `camelCase` 필드명 — 기존 도메인 패턴 준수.
4. **의미 있는 오류**: CONDUCT_004(카테고리 문제)·CONDUCT_005(학생 없음)·CONDUCT_006(역할 불일치) 세 가지 원인을 분리.
5. **확장성/성능**: 부여는 단순 INSERT라 동시성 레이스 없음. `@Version` 낙관적 락은 정정/취소 단계를 위해 지금 컬럼만 설립.
6. **하위 호환성**: 신규 엔드포인트·테이블이므로 기존 계약에 영향 없음.

**`points` 스냅샷**: 부여 시점 카테고리 값을 `ConductRecord.points`에 복사한다. `ConductCategory`의 점수가 나중에 변경되더라도 과거 기록은 바뀌지 않는다(감사 일관성).

**`teacherNickname` 필드명**: 마스터 기획서 원안은 `teacherName`이지만, 이 프로젝트는 `User.name`이 별명(닉네임)이므로 `teacherNickname`으로 통일해 `studentNickname`과 대칭을 맞춘다. 마스터 기획서와 다른 구현 세부사항으로 분류한다(계약 변경 아님).
