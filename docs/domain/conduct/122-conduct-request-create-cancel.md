# 상/벌점 요청 생성·취소 — 기능 기획서 (이슈 #122)

## 개요/목적
선도부(DISCIPLINE)는 상/벌점 부여 권한이 없어 조회만 가능하다(conduct 마스터 기획서 #58
"정책 가정" 참고). 이 공백을 메꾸기 위해, 선도부가 카테고리·사유를 직접 입력하고 부여
권한자(TEACHER 또는 ADMIN) 한 명을 지정해 상/벌점 부여를 요청하는 기능을 추가한다.

이슈 #122는 선도부 전용 쓰기 액션인 **요청 생성**(`POST /api/v1/conduct-requests`)과
**요청 취소**(`PATCH /api/v1/conduct-requests/{id}/cancel`)를 구현한다.

- **관련 마스터 기획서**: `docs/domain/conduct/1_conduct-domain.md`
- **선행 이슈**: #117 (`ConductRecord` 부여·조회 전체 완료)
- **후속 이슈**: #B (조회), #C (승인·거절)

---

## 확정 정책 (변경 불가)

- **거절 = 종료.** 거절된 요청을 다른 담당자에게 재배정하거나 재요청하는 기능은 없다.
  `REJECTED` 상태로 끝나며, 다시 요청하려면 선도부가 처음부터 새 요청을 만든다.
- **카테고리·사유는 요청 생성 시 선도부가 직접 입력한다.** 승인하는 담당자는 승인 시점에
  `categoryId`·`detail`을 수정할 수 있다(이슈 #C 범위).
- **PENDING 상태에서 요청자(선도부) 본인이 취소할 수 있다.** 하드 삭제가 아니라 `CANCELED`
  상태로 전환해 기록을 보존한다(`ConductRecord` 취소 패턴과 동일).

---

## 상태 전이

```
생성     → PENDING
PENDING  → APPROVED  (승인, 이슈 #C)
PENDING  → REJECTED  (거절, 이슈 #C)
PENDING  → CANCELED  (요청자 취소, 이슈 #122)
```

`APPROVED`·`REJECTED`·`CANCELED`는 최종 상태로, 이후 상태 전이 없다.

---

## 데이터 모델

### 신규 enum: `ConductRequestStatus`
```
PENDING  — 승인 대기 중
APPROVED — 승인됨 (ConductRecord 생성 완료)
REJECTED — 거절됨
CANCELED — 요청자가 취소함
```

### 신규 엔티티: `ConductRequest` (`conduct_request` 테이블)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | `BIGINT PK AUTO_INCREMENT` | 내부 PK |
| `requester_user_id` | `BIGINT FK → user.id NOT NULL` | 요청자 (DISCIPLINE) |
| `student_user_id` | `BIGINT FK → user.id NOT NULL` | 상/벌점 대상 학생 |
| `assignee_user_id` | `BIGINT FK → user.id NOT NULL` | 처리 담당자 (TEACHER 또는 ADMIN) |
| `category_id` | `BIGINT FK → conduct_category.id NOT NULL` | 요청 카테고리 |
| `detail` | `VARCHAR(500) NULL` | 추가 사유 (선택) |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'PENDING'` | `ConductRequestStatus` |
| `conduct_record_id` | `BIGINT FK → conduct_record.id NULL` | 승인 시 생성된 기록 (이슈 #C에서 채워짐) |
| `version` | `BIGINT NOT NULL DEFAULT 0` | 낙관적 락(`@Version`) |
| `created_at` | `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP` | |
| `updated_at` | `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | |
| `canceled_at` | `DATETIME NULL` | 취소 시각 |

인덱스:
- `(requester_user_id, created_at)` — 선도부 본인 요청 목록 조회 (이슈 #B)
- `(assignee_user_id, status)` — 담당자 배정 목록 조회 (이슈 #B)

> `conduct_record_id`는 이 이슈에서 컬럼만 추가하고 채우지 않는다. 승인(이슈 #C)에서
> `ConductRecord`를 생성한 뒤 링크한다. 처음부터 컬럼을 포함해 `ALTER TABLE`을 피한다.

### V{타임스탬프}__add_conduct_request.sql
`conduct_request` 테이블 생성. 시드 데이터 없음. 타임스탬프는 마이그레이션 실행 시각 기준
(`migration-convention.md` 참고).

### 에러 코드 추가 (`ConductErrorCode`)

기존 코드(CONDUCT_001~008)에 아래를 추가한다.

| 상수 | HTTP | 코드 | 메시지 |
|---|---|---|---|
| `REQUEST_NOT_FOUND` | 404 | `CONDUCT_009` | 상/벌점 요청을 찾을 수 없습니다. |
| `REQUEST_CANCEL_FORBIDDEN` | 403 | `CONDUCT_010` | 본인이 등록한 요청만 취소할 수 있습니다. |
| `REQUEST_NOT_CANCELLABLE` | 409 | `CONDUCT_011` | PENDING 상태의 요청만 취소할 수 있습니다. |
| `ASSIGNEE_NOT_FOUND` | 404 | `CONDUCT_012` | 배정 대상자를 찾을 수 없습니다. |
| `ASSIGNEE_INVALID_ROLE` | 400 | `CONDUCT_013` | 배정 대상자가 TEACHER 또는 ADMIN 역할이 아닙니다. |

> 카테고리(`CONDUCT_004`)·학생(`CONDUCT_005`·`CONDUCT_006`) 검증 에러는 기존 코드를
> 그대로 재사용한다.

---

## 엔드포인트 1: `POST /api/v1/conduct-requests` — 요청 생성

**권한**: `DISCIPLINE` (`@PreAuthorize("hasRole('DISCIPLINE')")`)

**요청**
```json
{
  "studentUserId": 101,
  "assigneeUserId": 42,
  "categoryId": 5,
  "detail": "3교시 10분 지각"
}
```
- `detail`은 선택 항목이다. 생략하면 `null`로 저장된다.
- `categoryId`는 `GET /api/v1/conduct-records/categories`로 받은 목록 중 하나다.
- `assigneeUserId`는 TEACHER 또는 ADMIN 역할을 가진 사용자여야 한다.

**응답** (`201 Created`)
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
    "status": "PENDING",
    "createdAt": "2026-09-01T09:15:00"
  },
  "message": "상/벌점 요청이 등록되었습니다.",
  "code": null
}
```

> `type`은 `ConductCategory.type`을 그대로 내려준다(응답 편의용, 별도 컬럼으로 저장하지
> 않는다). 요청·응답에서 `points`는 포함하지 않는다 — 승인자가 카테고리를 수정할 수 있어
> 확정 점수는 승인 시점 `ConductRecord`에서 결정되므로, 요청 단계에서 내려주면 오해를 유발한다.

**구현 로직**
1. `categoryId`로 `ConductCategory` 조회한다. 없거나 `active = false`이면 `400`
   `CONDUCT_004`를 반환한다.
2. `studentUserId`로 `User`를 조회한다. 없으면 `404` `CONDUCT_005`를 반환한다.
3. `studentUserId` 사용자가 `STUDENT` 역할인지 확인한다. 아니면 `400` `CONDUCT_006`을 반환한다.
4. `assigneeUserId`로 `User`를 조회한다. 없으면 `404` `CONDUCT_012`를 반환한다.
5. `assigneeUserId` 사용자가 `TEACHER` 또는 `ADMIN` 역할인지 확인한다. 아니면 `400`
   `CONDUCT_013`을 반환한다.
6. `ConductRequest`를 저장한다(`status = PENDING`, `requesterUserId`는
   `@AuthenticationPrincipal`에서 추출).
7. 저장된 엔티티를 `ConductRequestResponse`로 변환해 반환한다.

**에러**

| 조건 | HTTP | 코드 |
|---|---|---|
| `categoryId` 없거나 `active = false` | 400 | `CONDUCT_004` |
| `studentUserId`에 해당하는 사용자 없음 | 404 | `CONDUCT_005` |
| `studentUserId` 사용자가 STUDENT 역할 아님 | 400 | `CONDUCT_006` |
| `assigneeUserId`에 해당하는 사용자 없음 | 404 | `CONDUCT_012` |
| `assigneeUserId` 사용자가 TEACHER·ADMIN 역할 아님 | 400 | `CONDUCT_013` |

---

## 엔드포인트 2: `PATCH /api/v1/conduct-requests/{id}/cancel` — 요청 취소

**권한**: `DISCIPLINE` (`@PreAuthorize("hasRole('DISCIPLINE')")`)

소유권 체크(요청자 본인인지)는 서비스 코드에서 명시적 `if`로 확인한다(`ConductRecord`
취소·정정의 소유권 체크 패턴과 동일).

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
    "status": "CANCELED",
    "createdAt": "2026-09-01T09:15:00"
  },
  "message": "상/벌점 요청이 취소되었습니다.",
  "code": null
}
```

**구현 로직**
1. `id`로 `ConductRequest`를 조회한다. 없으면 `404` `CONDUCT_009`를 반환한다.
2. `principal.userId() != request.getRequester().getId()`이면 `403` `CONDUCT_010`을 반환한다.
3. `request.getStatus() != PENDING`이면 `409` `CONDUCT_011`을 반환한다.
4. `status = CANCELED`, `canceledAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"))` 설정한다.
5. `ConductRequestResponse.from(request)`로 변환해 반환한다.

**에러**

| 조건 | HTTP | 코드 |
|---|---|---|
| `id` 없음 | 404 | `CONDUCT_009` |
| 요청자 본인이 아님 | 403 | `CONDUCT_010` |
| PENDING 상태가 아님 | 409 | `CONDUCT_011` |

---

## 신규 파일 목록

| 파일 | 설명 |
|---|---|
| `conduct/enums/ConductRequestStatus.java` | `PENDING` / `APPROVED` / `REJECTED` / `CANCELED` |
| `conduct/entity/ConductRequest.java` | JPA 엔티티 |
| `conduct/repository/ConductRequestRepository.java` | JPA 레포지토리 |
| `conduct/dto/ConductRequestCreateRequest.java` | 생성 요청 DTO |
| `conduct/dto/ConductRequestResponse.java` | 응답 DTO |
| `conduct/service/ConductRequestService.java` | `createRequest`, `cancelRequest` 메서드 |
| `conduct/controller/ConductRequestController.java` | `POST /`, `PATCH /{id}/cancel` 핸들러 |
| `db/migration/V{타임스탬프}__add_conduct_request.sql` | 테이블 생성 |

기존 변경 파일:
- `conduct/exception/ConductErrorCode.java` — `CONDUCT_009`~`CONDUCT_013` 추가

---

## 테스트 계획

`ConductRequestServiceTest`에 `CreateRequest`·`CancelRequest` 중첩 클래스를 추가한다.

### `CreateRequest`

| 시나리오 | 검증 항목 |
|---|---|
| 정상 생성 | `ConductRequest` 저장 호출, 응답 DTO 필드 일치, `status = PENDING` |
| 비활성 카테고리 | `CONDUCT_004` 예외 발생 |
| 존재하지 않는 학생 | `CONDUCT_005` 예외 발생 |
| 학생 역할 아닌 대상 | `CONDUCT_006` 예외 발생 |
| 존재하지 않는 배정 대상자 | `CONDUCT_012` 예외 발생 |
| 배정 대상자가 TEACHER·ADMIN 아님 | `CONDUCT_013` 예외 발생 |

### `CancelRequest`

| 시나리오 | 검증 항목 |
|---|---|
| 정상 취소 | `status = CANCELED`, `canceledAt` 세팅 확인 |
| 존재하지 않는 요청 | `CONDUCT_009` 예외 발생 |
| 요청자 본인이 아님 | `CONDUCT_010` 예외 발생 |
| PENDING 아닌 상태(예: APPROVED) | `CONDUCT_011` 예외 발생 |

---

## API 설계 6원칙 체크

1. **한 가지를 잘하기**: 생성(선도부 → 요청 등록)과 취소(선도부 → 자기 요청 철회)만 담당한다.
   조회·승인·거절은 후속 이슈(#B·#C)에서 분리한다.
2. **빠른 시작**: 요청·응답 예시 JSON 포함. 에러 케이스 표로 명시.
3. **일관성**: 경로 `/api/v1/conduct-requests`, `ApiResponse<T>` 래퍼, `CONDUCT_NNN` 에러 코드,
   소유권 체크는 서비스 코드 — `ConductRecord` 취소 패턴과 동일. 취소에 `/cancel` 접미사를
   쓰는 것도 `PATCH /conduct-records/{id}/cancel`과 같은 컨벤션이다.
4. **의미 있는 오류**: `CONDUCT_010`(소유권 없음)과 `CONDUCT_011`(상태 불일치)을 분리해
   "권한이 없어서"와 "이미 처리된 요청이라서"를 구분한다. 배정 대상자 검증도 "없음"
   (`CONDUCT_012`)과 "역할 불일치"(`CONDUCT_013`)를 분리한다.
5. **확장성/성능**: 생성·취소 모두 단건 처리라 성능 이슈 없음. `@Version` 낙관적 락으로
   동시 취소 이중 클릭을 방어한다(#121 핸들러 머지 후 409로 정상 처리됨).
6. **하위 호환성**: 신규 엔드포인트·테이블이므로 기존 계약에 영향 없음.

---

## 리스크 및 고려사항

- **소유권 체크 누락**: 다른 선도부가 남의 요청을 취소하는 IDOR이 가장 위험한 실수 지점.
  단위 테스트에서 반드시 검증한다.
- **`assigneeUserId` 역할 검증**: TEACHER·ADMIN 여부는 `userRoleRepository`로 확인한다
  (`outing` #29의 OUTING_002 패턴 참고 — 담당자 역할을 서버에서 강제 검증하는 동일 원칙).
- **`points` 미포함**: 응답에 `points`를 내려주지 않는다. 승인자가 카테고리를 바꿀 수 있어
  요청 시점 점수가 확정 점수가 아니기 때문이다. 클라이언트는 카테고리 목록 API에서 점수를
  표시하면 된다.
- **`@Version` 낙관적 락**: `ConductRequest`에 `@Version`을 달면 동시 상태 변경(예: 승인과
  취소가 동시에 들어오는 경우) 시 `ObjectOptimisticLockingFailureException`이 발생한다.
  현재 `GlobalExceptionHandler`에 핸들러가 없어 500이 반환되는 문제가 있다(이슈 #121).
  #121 머지 전까지는 이 엣지케이스에서 500이 나가며, #121 머지 후 409로 정상 처리된다.
- **KST 시각**: `canceledAt`은 `ZoneId.of("Asia/Seoul")` 기준 — `outing`·`ConductRecord`와
  동일한 패턴.
