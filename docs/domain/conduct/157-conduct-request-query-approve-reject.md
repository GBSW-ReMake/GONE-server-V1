# 상/벌점 요청 조회·승인·거절 — 기능 기획서 (이슈 #157)

## 개요/목적

`ConductRequest` 생성·취소(#122)가 완료됐다. 선도부가 생성한 요청을 담당 교사(TEACHER)·관리자(ADMIN)가
조회하고 승인하거나 거절하는 기능이 없어 플로우가 완결되지 않는다. 이 이슈에서 나머지 세 엔드포인트를 추가한다.

- **관련 마스터 기획서**: `docs/domain/conduct/1_conduct-domain.md`
- **선행 이슈**: #122 (요청 생성·취소)
- **관련 이슈**: #121 (낙관적 락 409 핸들러, 이미 머지됨)

---

## 상태 전이 (전체, #122 포함)

```
생성     → PENDING
PENDING  → APPROVED  (승인, 이 이슈)
PENDING  → REJECTED  (거절, 이 이슈)
PENDING  → CANCELED  (요청자 취소, #122)
```

`APPROVED`·`REJECTED`·`CANCELED`는 최종 상태. 이후 상태 전이 없음.

---

## 에러 코드 추가 (`ConductErrorCode`)

기존 코드(`CONDUCT_009`~`CONDUCT_013`)에 아래를 추가한다.

| 상수 | HTTP | 코드 | 메시지 |
|---|---|---|---|
| `REQUEST_APPROVE_FORBIDDEN` | 403 | `CONDUCT_014` | 해당 요청의 담당자만 승인·거절할 수 있습니다. |
| `REQUEST_NOT_PROCESSABLE` | 409 | `CONDUCT_015` | PENDING 상태의 요청만 승인·거절할 수 있습니다. |

---

## 응답 DTO 변경: `ConductRequestResponse`

기존 필드에 `conductRecordId`를 추가한다. 승인 전(`APPROVED`가 아닌 상태)에는 `null`, 승인 후에는
`ConductRecord`의 `id`가 채워진다. 기존 필드를 제거하거나 타입을 바꾸지 않으므로 하위 호환 변경이다.

```java
public record ConductRequestResponse(
    Long id,
    Long requesterUserId,
    String requesterNickname,
    Long studentUserId,
    String studentNickname,
    Long assigneeUserId,
    String assigneeNickname,
    Long categoryId,
    String categoryLabel,
    ConductType type,
    String detail,
    ConductRequestStatus status,
    Long conductRecordId,   // 신규 추가 — APPROVED 이전에는 null
    LocalDateTime createdAt
) { ... }
```

---

## 엔드포인트 1: `GET /api/v1/conduct-requests` — 요청 목록 조회

**권한**: `DISCIPLINE`, `TEACHER`, `ADMIN` (`@PreAuthorize("hasAnyRole('DISCIPLINE','TEACHER','ADMIN')")`)

역할에 따라 서비스에서 조회 범위를 다르게 적용한다.

| 역할 | 조회 범위 |
|---|---|
| `DISCIPLINE` | 본인이 생성한 요청(`requester_user_id = self`) |
| `TEACHER` | 본인에게 배정된 요청(`assignee_user_id = self`) |
| `ADMIN` | 전체 요청 |

**쿼리 파라미터**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `status` | `String` | 선택 | `PENDING` / `APPROVED` / `REJECTED` / `CANCELED`. 생략 시 전체 반환 |
| `page` | `int` | 선택 | 0-based, 기본 0 |
| `size` | `int` | 선택 | 기본 20 |

> `CONDUCT_007`(페이지 파라미터 오류)은 기존 에러 코드. `status` 값이 유효하지 않으면
> `400 COMMON_001` (Spring의 `MethodArgumentTypeMismatchException` 핸들러).

**응답** (`200 OK`)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "requesterUserId": 33,
        "requesterNickname": "홍선도",
        "studentUserId": 101,
        "studentNickname": "길동이",
        "assigneeUserId": 42,
        "assigneeNickname": "김선생",
        "categoryId": 5,
        "categoryLabel": "지각",
        "type": "DEMERIT",
        "detail": "3교시 10분 지각",
        "status": "PENDING",
        "conductRecordId": null,
        "createdAt": "2026-09-01T09:15:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  },
  "message": null,
  "code": null
}
```

> `PageResponse<T>` — 기존 `ConductService.getStaffRecords` 패턴과 동일.

**구현 로직**
1. `principal`의 역할 목록(`userRoleRepository.findRoleCodesByUserId`)으로 역할 분기.
2. `status` 파라미터가 있으면 `ConductRequestStatus`로 변환 후 필터에 추가.
   없으면 `null`로 전달해 전체 조회.
3. 역할별로 `ConductRequestRepository`의 별도 쿼리 메서드 호출 + 페이지네이션 적용.
4. `Page<ConductRequest>` → `PageResponse<ConductRequestResponse>` 변환 후 반환.

**에러**

| 조건 | HTTP | 코드 |
|---|---|---|
| 인증 없음 | 401 | `COMMON_002` |
| 권한 없음(STUDENT 등) | 403 | `COMMON_003` |
| `status` 파라미터 값이 enum 불일치 | 400 | `COMMON_001` |

---

## 엔드포인트 2: `PATCH /api/v1/conduct-requests/{id}/approve` — 요청 승인

**권한**: `TEACHER`, `ADMIN` (`@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")`)

- TEACHER: 자신이 assignee인 요청만 승인 가능(소유권 검사, 서비스에서 명시적 `if`로 확인).
- ADMIN: 어떤 요청도 승인 가능.

승인 시 `ConductRecord`를 생성하고 `ConductRequest.conductRecordId`에 링크한다.
승인자가 카테고리·상세 사유를 수정할 수 있다(#122 확정 정책).

**요청**

```json
{
  "categoryId": 5,
  "detail": "3교시 10분 지각 (확인됨)"
}
```

- `categoryId`: 선택. 생략 시 요청 원본 카테고리 사용.
- `detail`: 선택. 생략 시 요청 원본 상세 사유 사용 (`null`이면 `null` 그대로).

**응답** (`200 OK`)

```json
{
  "success": true,
  "data": {
    "id": 1,
    "requesterUserId": 33,
    "requesterNickname": "홍선도",
    "studentUserId": 101,
    "studentNickname": "길동이",
    "assigneeUserId": 42,
    "assigneeNickname": "김선생",
    "categoryId": 5,
    "categoryLabel": "지각",
    "type": "DEMERIT",
    "detail": "3교시 10분 지각 (확인됨)",
    "status": "APPROVED",
    "conductRecordId": 7,
    "createdAt": "2026-09-01T09:15:00"
  },
  "message": "상/벌점 요청이 승인되었습니다.",
  "code": null
}
```

**구현 로직**
1. `id`로 `ConductRequest` 조회. 없으면 `404` `CONDUCT_009`.
2. TEACHER 역할인 경우: `approverUserId != request.getAssignee().getId()`이면 `403` `CONDUCT_014`.
3. `request.getStatus() != PENDING`이면 `409` `CONDUCT_015`.
4. `categoryId` 오버라이드가 있으면 `ConductCategory` 조회. 없거나 inactive이면 `400` `CONDUCT_004`.
5. `ConductRecord` 생성:
   - `student` = `request.getStudent()`
   - `teacher` = approver (`userRepository.findById(approverUserId)`)
   - `category` = 오버라이드 카테고리 또는 `request.getCategory()`
   - `type` = `category.getType()` (스냅샷)
   - `points` = `category.getPoints()` (스냅샷)
   - `detail` = 오버라이드 detail 또는 `request.getDetail()`
6. `conductRecordRepository.save(record)` → id 획득.
7. `request.setStatus(APPROVED)`, `request.setConductRecordId(record.getId())`.
8. `ConductRequestResponse.from(request)` 반환.

> `ConductRequest`에는 `@Version`이 있다. 두 명이 동시에 같은 요청을 승인하면
> `ObjectOptimisticLockingFailureException` → 409 (`#121` 핸들러로 처리됨).

**에러**

| 조건 | HTTP | 코드 |
|---|---|---|
| `id` 없음 | 404 | `CONDUCT_009` |
| TEACHER가 assignee 아님 | 403 | `CONDUCT_014` |
| PENDING이 아닌 상태 | 409 | `CONDUCT_015` |
| 오버라이드 `categoryId` 없거나 inactive | 400 | `CONDUCT_004` |

---

## 엔드포인트 3: `PATCH /api/v1/conduct-requests/{id}/reject` — 요청 거절

**권한**: `TEACHER`, `ADMIN` (`@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")`)

- TEACHER: 자신이 assignee인 요청만 거절 가능.
- ADMIN: 어떤 요청도 거절 가능.

**요청**: 바디 없음.

**응답** (`200 OK`)

```json
{
  "success": true,
  "data": {
    "id": 1,
    "requesterUserId": 33,
    "requesterNickname": "홍선도",
    "studentUserId": 101,
    "studentNickname": "길동이",
    "assigneeUserId": 42,
    "assigneeNickname": "김선생",
    "categoryId": 5,
    "categoryLabel": "지각",
    "type": "DEMERIT",
    "detail": "3교시 10분 지각",
    "status": "REJECTED",
    "conductRecordId": null,
    "createdAt": "2026-09-01T09:15:00"
  },
  "message": "상/벌점 요청이 거절되었습니다.",
  "code": null
}
```

**구현 로직**
1. `id`로 `ConductRequest` 조회. 없으면 `404` `CONDUCT_009`.
2. TEACHER 역할인 경우: assignee 소유권 확인. 아니면 `403` `CONDUCT_014`.
3. `request.getStatus() != PENDING`이면 `409` `CONDUCT_015`.
4. `request.setStatus(REJECTED)`.
5. `ConductRequestResponse.from(request)` 반환.

**에러**

| 조건 | HTTP | 코드 |
|---|---|---|
| `id` 없음 | 404 | `CONDUCT_009` |
| TEACHER가 assignee 아님 | 403 | `CONDUCT_014` |
| PENDING이 아닌 상태 | 409 | `CONDUCT_015` |

---

## 신규·변경 파일 목록

| 파일 | 변경 |
|---|---|
| `conduct/dto/ConductRequestApproveRequest.java` | 신규 — 승인 요청 DTO |
| `conduct/dto/ConductRequestResponse.java` | 변경 — `conductRecordId` 필드 추가 |
| `conduct/repository/ConductRequestRepository.java` | 변경 — 역할별 조회 쿼리 메서드 추가 |
| `conduct/service/ConductRequestService.java` | 변경 — `getRequests`, `approveRequest`, `rejectRequest` 추가 |
| `conduct/controller/ConductRequestController.java` | 변경 — 3개 엔드포인트 추가 |
| `conduct/exception/ConductErrorCode.java` | 변경 — `CONDUCT_014`·`CONDUCT_015` 추가 |

DB 마이그레이션 없음 — `conduct_request` 테이블 스키마 변경 없음.

---

## 리포지토리 쿼리 설계

`ConductRequestRepository`에 추가할 메서드:

```java
// DISCIPLINE: 내가 생성한 요청
Page<ConductRequest> findByRequesterIdOrderByCreatedAtDesc(Long requesterId, Pageable pageable);
Page<ConductRequest> findByRequesterIdAndStatusOrderByCreatedAtDesc(
    Long requesterId, ConductRequestStatus status, Pageable pageable);

// TEACHER: 나에게 배정된 요청
Page<ConductRequest> findByAssigneeIdOrderByCreatedAtDesc(Long assigneeId, Pageable pageable);
Page<ConductRequest> findByAssigneeIdAndStatusOrderByCreatedAtDesc(
    Long assigneeId, ConductRequestStatus status, Pageable pageable);

// ADMIN: 전체
Page<ConductRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);
Page<ConductRequest> findByStatusOrderByCreatedAtDesc(
    ConductRequestStatus status, Pageable pageable);
```

> `(requester_user_id, created_at)`, `(assignee_user_id, status)` 인덱스는 #122 마이그레이션에
> 이미 포함되어 있다.

---

## 테스트 계획

`ConductRequestServiceTest`에 아래 중첩 클래스를 추가한다.

### `GetRequests`

| 시나리오 | 검증 항목 |
|---|---|
| DISCIPLINE — PENDING 필터 | `findByRequesterIdAndStatus` 호출, 결과 반환 |
| DISCIPLINE — 필터 없음 | `findByRequesterId` 호출 |
| TEACHER — 배정 목록 | `findByAssigneeIdAndStatus` 호출 |
| ADMIN — 전체 | `findAllBy` 호출 |

### `ApproveRequest`

| 시나리오 | 검증 항목 |
|---|---|
| TEACHER(assignee)가 정상 승인 | `ConductRecord` 저장 호출, `status = APPROVED`, `conductRecordId` 세팅 |
| ADMIN이 정상 승인 | 소유권 검사 없이 승인 |
| 카테고리 오버라이드 있음 | 오버라이드 카테고리로 `ConductRecord` 생성 |
| TEACHER가 assignee 아님 | `CONDUCT_014` 예외 |
| PENDING 아닌 상태 | `CONDUCT_015` 예외 |
| 오버라이드 카테고리 없거나 inactive | `CONDUCT_004` 예외 |
| 요청 없음 | `CONDUCT_009` 예외 |

### `RejectRequest`

| 시나리오 | 검증 항목 |
|---|---|
| TEACHER(assignee)가 정상 거절 | `status = REJECTED` |
| ADMIN이 정상 거절 | 소유권 검사 없이 거절 |
| TEACHER가 assignee 아님 | `CONDUCT_014` 예외 |
| PENDING 아닌 상태 | `CONDUCT_015` 예외 |
| 요청 없음 | `CONDUCT_009` 예외 |

---

## API 설계 6원칙 체크

1. **한 가지를 잘하기**: 조회·승인·거절 3개 엔드포인트. 조회는 읽기 전용이고 승인·거절은
   `ConductRequest`의 상태 전이만 담당한다. 승인은 `ConductRecord` 생성을 포함하지만
   하나의 원자 트랜잭션으로 묶어 "승인 = 기록 생성 + 상태 전이"라는 단일 목적에 부합한다.
2. **빠른 시작**: 요청/응답 JSON 예시 포함. 에러 케이스 표 명시.
3. **일관성**:
   - 경로: `/api/v1/conduct-requests` + 동사 접미사(기존 `/cancel` 패턴과 동일).
   - 소유권 체크: 서비스 코드 명시적 `if` — `ConductRecord` 취소·정정 및 `ConductRequest`
     취소와 동일 패턴.
   - 에러 코드: `CONDUCT_014`·`CONDUCT_015` 신규 추가, 기존 번호 체계(`_NNN`) 유지.
   - `PageResponse<T>`: 기존 `ConductService.getStaffRecords` 패턴 그대로.
4. **의미 있는 오류**: 소유권 없음(`CONDUCT_014`)과 상태 불일치(`CONDUCT_015`) 분리.
   카테고리 오버라이드 오류는 기존 `CONDUCT_004` 재사용(같은 의미).
5. **확장성/성능**: DB 페이지네이션 사용(`Pageable`). 기존 인덱스
   `(requester_user_id, created_at)`, `(assignee_user_id, status)` 활용 — 인덱스 추가 불필요.
6. **하위 호환성**: `ConductRequestResponse`에 `conductRecordId` 추가는 신규 필드로 기존
   클라이언트에 영향 없음.

---

## 리스크 및 고려사항

- **TEACHER 소유권 체크 — IDOR 위험**: TEACHER가 자신에게 배정되지 않은 요청을 승인·거절하는
  IDOR이 가장 위험한 실수 지점. 서비스에서 `approverUserId != request.getAssignee().getId()`로
  명시적 검사. 단위 테스트에서 반드시 커버.
- **ADMIN 소유권 우회**: ADMIN은 소유권 검사 없이 모든 요청을 처리할 수 있다(프로젝트 전체
  공통 전제). 서비스에서 역할을 확인(`userRoleRepository`)해 ADMIN이면 소유권 검사 스킵.
- **승인 트랜잭션 원자성**: `ConductRecord` 저장 + `ConductRequest` 상태·`conductRecordId`
  업데이트는 단일 `@Transactional` 내에서 처리. 중간에 예외가 나면 둘 다 롤백되어 일관성 유지.
- **낙관적 락 동시 승인**: 두 담당자가 동시에 같은 요청을 승인·거절하면
  `ObjectOptimisticLockingFailureException` → 409 (#121 핸들러).
- **거절 사유 미지원**: 현재 `ConductRequest` 엔티티에 `rejected_reason` 컬럼이 없다.
  이번 범위에서는 추가하지 않고, 필요 시 후속 이슈에서 ALTER TABLE + 응답 필드 추가로 확장한다.
- **조회 역할 분기 복잡도**: 서비스에서 역할별로 다른 쿼리 메서드를 호출하는 if-else가 생긴다.
  클래스 분리 없이 `ConductRequestService` 내부에서 처리한다(현재 규모에서 과도한 추상화 지양).
