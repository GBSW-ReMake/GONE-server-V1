# 상/벌점 정정·취소 — 기능 기획서 (이슈 #107)

## 개요/목적
교사(또는 ADMIN)가 이미 부여된 상/벌점 기록을 정정하거나 취소한다.

- **정정**: 카테고리(`categoryId`)와 상세 사유(`detail`)를 수정한다. 대상 학생(`studentUserId`)은 변경할 수 없다.
- **취소**: 기록 상태를 `ACTIVE` → `CANCELED`로 전환한다. 되돌릴 수 없다.

두 엔드포인트는 동일한 소유권 체크 로직과 에러 코드 세트(CONDUCT_001·002·003)를 공유해 한 이슈에서 함께 구현한다.

- **관련 마스터 기획서**: [`docs/domain/conduct/1_conduct-domain.md`](./1_conduct-domain.md) — "2. 정정" / "3. 취소" 절
- **선행 이슈**: #94 (`ConductRecord` 엔티티·V16 마이그레이션·부여 API)

---

## 에러 코드 추가 (`ConductErrorCode`)

기존 코드(CONDUCT_004~006)에 아래 3개를 추가한다.

| 코드 | HTTP | 메시지 |
|---|---|---|
| `CONDUCT_001` | 404 | 상/벌점 기록을 찾을 수 없습니다. |
| `CONDUCT_002` | 403 | 본인이 부여한 기록만 처리할 수 있습니다. |
| `CONDUCT_003` | 409 | 이미 취소된 기록입니다. |

---

## 엔드포인트 1: `PATCH /api/v1/conduct-records/{id}` — 정정

**권한**: `TEACHER` 또는 `ADMIN`  
(`@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")`)  
`TEACHER`인 경우 본인이 부여한 기록인지 서비스 코드에서 소유권 확인.

**요청** (`categoryId`, `detail` 둘 다 선택 — 보낸 필드만 갱신)
```json
{ "categoryId": 6, "detail": "3교시 이후 조퇴, 사유서 미제출" }
```
- `categoryId`만 보내거나, `detail`만 보내거나, 둘 다 보낼 수 있다.
- **두 필드 모두 `null`인 경우(빈 바디 포함)는 `400` `COMMON_001 INVALID_REQUEST` 반환.** 변경할 내용이 없는 요청은 거부한다.
- `detail`에 빈 문자열(`""`) 허용 여부: `null`은 "변경 없음"으로, `""` 전달 시 그대로 저장한다.
- `categoryId`를 보내면 `type`/`points`도 새 카테고리 기준으로 재계산해 스냅샷.

**응답** (`200 OK`) — 부여(#94) 응답 DTO와 동일한 `ConductRecordResponse` 구조

```json
{
  "success": true,
  "data": {
    "id": 501,
    "studentUserId": 101,
    "studentNickname": "길동이",
    "teacherUserId": 42,
    "teacherNickname": "김선생",
    "categoryId": 6,
    "categoryLabel": "무단조퇴",
    "type": "DEMERIT",
    "points": -3,
    "detail": "3교시 이후 조퇴, 사유서 미제출",
    "status": "ACTIVE",
    "createdAt": "2026-08-12T09:15:00"
  },
  "message": "상/벌점 기록이 정정되었습니다."
}
```

**구현 로직**
1. `id`로 `ConductRecord` 조회. 없으면 `404` `CONDUCT_001`.
2. 호출자가 `ADMIN`이 아니고 `principal.userId() != record.getTeacher().getId()`이면 `403` `CONDUCT_002`.
3. `record.getStatus() == CANCELED`이면 `409` `CONDUCT_003`.
4. 요청에 `categoryId`가 있으면 해당 `ConductCategory` 조회. 없거나 `active = false`이면 `400` `CONDUCT_004`. 조회 성공 시 `category`, `type`, `points` 재계산해 갱신.
5. 요청에 `detail`이 있으면(`null`이 아니면) 갱신.
6. 저장 후 `ConductRecordResponse.from(record)` 반환.

**에러**

| 조건 | HTTP | 코드 |
|---|---|---|
| `categoryId`·`detail` 모두 null | 400 | `COMMON_001` |
| `id` 없음 | 404 | `CONDUCT_001` |
| 소유권 없음(ADMIN 아닌 경우) | 403 | `CONDUCT_002` |
| 이미 취소됨 | 409 | `CONDUCT_003` |
| `categoryId` 없거나 비활성화 | 400 | `CONDUCT_004` |

---

## 엔드포인트 2: `PATCH /api/v1/conduct-records/{id}/cancel` — 취소

**권한**: 정정과 동일 (`TEACHER` 또는 `ADMIN`, 소유권 체크)

**요청**
```json
{ "cancelReason": "학생 확인 결과 오인 부여로 확인됨" }
```
- `cancelReason`은 필수 항목이다. 취소 사유 없이 취소할 수 없다.

**응답** (`200 OK`)

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
    "status": "CANCELED",
    "createdAt": "2026-08-12T09:15:00"
  },
  "message": "상/벌점 기록이 취소되었습니다."
}
```

**구현 로직**
1~3단계는 정정과 동일(조회 → 소유권 → 상태 확인).
4. `record.setStatus(CANCELED)`, `record.setCanceledAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")))`, `record.setCanceledBy(cancelingUser)`, `record.setCancelReason(request.cancelReason())` 설정.
5. 저장 후 `ConductRecordResponse.from(record)` 반환.

**에러**: 정정과 동일한 코드 체계(CONDUCT_001~003).

---

## 데이터 모델 변경

없음. `ConductRecord` 엔티티에 `canceledAt`, `canceledBy`, `cancelReason` 필드가 이미 존재한다(#94 V16 마이그레이션).

---

## 영향 받는 기존 코드

- `ConductErrorCode`: CONDUCT_001~003 추가
- `ConductRecordRepository`: `findById`는 이미 `JpaRepository` 기본 제공. 별도 커스텀 메서드 불필요.
- `ConductService`: `amendConduct`, `cancelConduct` 메서드 추가
- `ConductController`: `@PatchMapping("/{id}")`, `@PatchMapping("/{id}/cancel")` 추가
- 신규 DTO: `ConductAmendRequest`, `ConductCancelRequest`
- 응답 DTO: 기존 `ConductRecordResponse` 재사용 (변경 없음)

---

## API 설계 6원칙 체크

1. **한 가지를 잘하기**: 정정과 취소를 별도 엔드포인트로 분리. 취소는 되돌릴 수 없으므로 명시적으로 `/cancel` 경로를 씀.
2. **빠른 시작**: 요청/응답 예시 포함.
3. **일관성**: 경로 `/api/v1/conduct-records/{id}`, `ApiResponse<T>` 래퍼, `ErrorCode` 네이밍(`CONDUCT_NNN`) 기존 패턴 그대로. 소유권 체크는 서비스 코드(`outing`과 동일 원칙).
4. **의미 있는 오류**: CONDUCT_002(소유권 없음)와 CONDUCT_003(이미 취소됨)을 분리 — "권한이 없어서"와 "상태가 맞지 않아서"를 구분.
5. **확장성/성능**: 단건 조회·갱신이라 성능 이슈 없음.
6. **하위 호환성**: 기존 응답 필드 변경 없음. `ConductRecordResponse` 구조 그대로 재사용.

---

## 리스크 및 고려사항

- **소유권 체크 누락**: ADMIN이 아닌 교사가 타인 기록을 수정하는 IDOR이 가장 위험한 실수 지점. 단위 테스트에서 반드시 검증.
- **`cancelReason` 필수 처리**: `@NotBlank` 어노테이션으로 빈 문자열도 거부. `GlobalExceptionHandler`의 `MethodArgumentNotValidException` 핸들러가 `400 INVALID_REQUEST`로 처리.
- **취소 불가역성**: 서비스 코드에서 취소 상태 검증(CONDUCT_003)으로 이중 취소 방지.
- **동시성**: 같은 기록에 동시 정정/취소 이중 클릭은 낙관적 락(`@Version`, 이미 V16에 존재)으로 처리됨. 별도 락 전략 불필요.
- **KST 시각**: `canceledAt`은 `ZoneId.of("Asia/Seoul")` 기준 — `outing` 도메인과 동일한 패턴.
