# #128 알림 읽음 처리 기획서

> 관련 Issue: [#128 알림 읽음 처리 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/128)
>
> 선행 기능: [#119 알림 목록 조회](./119-notification-list.md)
>
> 마스터 기획서: [1_notification-domain.md](./1_notification-domain.md)

## 1. 배경 및 목적

사용자가 알림함에서 특정 알림을 선택해 내용을 확인하면, 앱은 그 알림을 읽음 상태로 바꾼다.
사용자는 알림함의 모든 알림을 한 번에 읽음 처리할 수도 있다. 홈 화면은 안 읽은 알림 개수로
새 알림 뱃지 또는 점을 표시한다.

이번 이슈는 단건 읽음 처리, 전체 읽음 처리, 안 읽은 개수 조회를 함께 구현한다. 세 기능은
모두 Access Token에서 추출한 사용자 ID를 기준으로 본인 알림만 다룬다.

## 2. 범위

### 포함

- `PATCH /api/v1/notifications/{id}/read` 단건 읽음 처리
- `PATCH /api/v1/notifications/read-all` 전체 읽음 처리
- `GET /api/v1/notifications/unread-count` 안 읽은 개수 조회
- `NotificationErrorCode`에 `NOTIFICATION_001`, `NOTIFICATION_002` 추가
- 정상·예외 단위 테스트와 HTTP 통합 테스트

### 제외

- 알림 목록 조회 API 변경
- FCM 디바이스 토큰 등록·삭제 및 푸시 발송
- 외출·스쿨캠핑·상벌점 도메인의 알림 발송 연동
- 딥링크 연결

## 3. 공통 권한 및 보안

- 세 엔드포인트는 인증된 사용자만 호출할 수 있다.
- Controller는 `@AuthenticationPrincipal UserPrincipal`에서 사용자 ID를 얻는다.
- 요청 파라미터나 요청 본문으로 사용자 ID를 받지 않는다.
- 단건 읽음 처리는 조회한 `Notification.user.id`와 현재 사용자 ID를 비교한다. 다른 사용자의
  알림이면 `403 NOTIFICATION_002`를 반환한다.
- 전체 읽음과 안 읽은 개수 조회는 Repository 쿼리에 현재 사용자 ID를 직접 전달한다.

## 4. API 명세

### 4.1 `PATCH /api/v1/notifications/{id}/read`

사용자가 알림함에서 선택한 본인 알림 한 건을 읽음 처리한다.

#### 요청

```http
PATCH /api/v1/notifications/501/read
Authorization: Bearer {accessToken}
```

요청 본문은 없다.

#### 성공 응답

HTTP 상태 코드: `200 OK`

```json
{
  "success": true,
  "data": null,
  "message": "알림을 읽음 처리했습니다.",
  "code": null
}
```

이미 읽은 알림을 다시 요청해도 같은 응답을 반환한다. 클라이언트는 재시도 여부와 관계없이
읽음 상태를 안전하게 유지할 수 있다.

#### 오류 응답

| 상황 | HTTP 상태 | 에러 코드 |
|---|---:|---|
| 인증 정보가 없거나 유효하지 않음 | `401` | `COMMON_002` |
| 알림이 존재하지 않음 | `404` | `NOTIFICATION_001` |
| 다른 사용자의 알림을 요청함 | `403` | `NOTIFICATION_002` |

```json
{
  "success": false,
  "data": null,
  "message": "본인 알림만 처리할 수 있습니다.",
  "code": "NOTIFICATION_002"
}
```

#### 처리 순서

1. Repository가 알림 ID로 `Notification`을 조회한다.
2. 알림이 없으면 `NOTIFICATION_001` 예외를 일으킨다.
3. Service가 알림 수신자 ID와 현재 사용자 ID를 비교한다.
4. ID가 다르면 `NOTIFICATION_002` 예외를 일으킨다.
5. `Notification`의 읽음 상태를 `true`로 변경한다.

### 4.2 `PATCH /api/v1/notifications/read-all`

현재 사용자의 읽지 않은 알림을 모두 읽음 처리한다.

#### 요청

```http
PATCH /api/v1/notifications/read-all
Authorization: Bearer {accessToken}
```

요청 본문은 없다.

#### 성공 응답

HTTP 상태 코드: `200 OK`

```json
{
  "success": true,
  "data": null,
  "message": "모든 알림을 읽음 처리했습니다.",
  "code": null
}
```

읽지 않은 알림이 없어도 `200 OK`를 반환한다.

#### 오류 응답

| 상황 | HTTP 상태 | 에러 코드 |
|---|---:|---|
| 인증 정보가 없거나 유효하지 않음 | `401` | `COMMON_002` |

### 4.3 `GET /api/v1/notifications/unread-count`

현재 사용자가 읽지 않은 알림 개수를 반환한다.

#### 요청

```http
GET /api/v1/notifications/unread-count
Authorization: Bearer {accessToken}
```

요청 파라미터와 요청 본문은 없다.

#### 성공 응답

HTTP 상태 코드: `200 OK`

```json
{
  "success": true,
  "data": {
    "unreadCount": 3
  },
  "message": "안 읽은 알림 개수를 조회했습니다.",
  "code": null
}
```

#### 오류 응답

| 상황 | HTTP 상태 | 에러 코드 |
|---|---:|---|
| 인증 정보가 없거나 유효하지 않음 | `401` | `COMMON_002` |

## 5. 구현 구조

### Controller

- 기존 `NotificationController`에 `PATCH`, `GET` 메서드 세 개를 추가한다.
- 모든 메서드에 `@PreAuthorize("isAuthenticated()")`를 적용한다.
- Controller는 사용자 ID를 Service에 전달하고 `ApiResponse.success(...)`만 반환한다.
- Controller는 알림 조회, 소유권 비교, 상태 변경을 직접 수행하지 않는다.

### Service

- `markAsRead(Long userId, Long notificationId)`는 단건 조회, 소유권 확인, 상태 변경을
  하나의 트랜잭션에서 처리한다.
- `markAllAsRead(Long userId)`는 전체 읽음 Repository 쿼리를 호출한다.
- `getUnreadCount(Long userId)`는 Repository의 `COUNT` 결과를 응답 DTO로 변환한다.

### Entity

- `Notification`에 읽음 상태를 `true`로 바꾸는 의도 명확한 메서드를 추가한다.
- Service가 `isRead` 필드에 직접 접근하거나 setter를 추가하지 않는다.

### Repository

- 단건 처리에는 기존 `findById`를 사용한다.
- 전체 읽음에는 다음 JPQL 벌크 갱신 쿼리를 추가한다.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Notification n set n.isRead = true "
    + "where n.user.id = :userId and n.isRead = false")
int markAllAsReadByUserId(@Param("userId") Long userId);
```

- 안 읽은 개수에는 사용자 ID와 `isRead = false` 조건을 포함한 `COUNT` 쿼리를 추가한다.
- 전체 읽음 쿼리는 영속성 컨텍스트를 비운다. 같은 트랜잭션에서 이전 `Notification` 객체를
  읽었더라도 오래된 읽음 상태를 다시 사용하지 않게 한다.

### DTO 및 에러 코드

- `UnreadNotificationCountResponse(long unreadCount)`를 추가한다.
- `NOTIFICATION_001`: `404`, `알림을 찾을 수 없습니다.`
- `NOTIFICATION_002`: `403`, `본인 알림만 처리할 수 있습니다.`
- 기존 `NOTIFICATION_003`은 목록 조회의 페이지 파라미터 검증에 계속 사용한다.

## 6. 데이터베이스 및 하위 호환성

- 기존 `notification.is_read` 컬럼을 사용한다.
- `V20260903175511__add_notification_user_read_index.sql`로
  `idx_notification_user_read (user_id, is_read)` 복합 인덱스를 추가한다. 전체 읽음의
  조건부 벌크 UPDATE와 안 읽은 개수의 `COUNT`가 모두 `user_id`와 `is_read = false`로
  대상을 좁히므로, 목록 조회용 기존 `(user_id, created_at)` 인덱스와 별도로 필요하다.
- 기존 목록 조회 응답과 `Notification` 필드를 변경하지 않는다.
- 새 엔드포인트와 새 DTO만 추가하므로 기존 클라이언트 동작에는 영향을 주지 않는다.

## 7. 테스트 계획

### Service 단위 테스트

- 본인 알림을 읽음 처리한다.
- 이미 읽은 알림을 다시 읽음 처리해도 성공한다.
- 존재하지 않는 알림 ID는 `NOTIFICATION_001`을 반환한다.
- 다른 사용자의 알림은 `NOTIFICATION_002`를 반환한다.
- 전체 읽음 처리에서 현재 사용자 ID가 벌크 쿼리에 전달된다.
- 안 읽은 개수를 `UnreadNotificationCountResponse`로 반환한다.

### Controller/API 통합 테스트

- 인증된 사용자가 단건 읽음 처리에서 `200 OK`를 받는다.
- 인증된 사용자가 전체 읽음 처리에서 `200 OK`를 받는다.
- 인증된 사용자가 안 읽은 개수 응답을 받는다.
- 단건 읽음 처리에서 `404 NOTIFICATION_001`과 `403 NOTIFICATION_002`를 받는다.
- 인증되지 않은 요청은 세 엔드포인트 모두 `401 COMMON_002`를 반환한다.

## 8. 리스크 및 고려사항

1. **단일 책임**: 세 엔드포인트는 모두 읽음 상태와 읽지 않은 개수를 다루며, 알림 발송·딥링크·FCM은 포함하지 않는다. 단건과 전체 처리가 같은 소유권·상태 전이 모델을 공유하므로 한 이슈로 묶는다.
2. **빠른 연동**: 요청 본문 없이 Access Token만으로 호출한다. 성공·오류 JSON 예시를 문서에 제공한다.
3. **일관성**: 기존 `/api/v1/notifications` 경로, `ApiResponse<T>`, `UserPrincipal`,
   `NotificationErrorCode` 패턴을 재사용한다.
4. **의미 있는 오류**: 존재하지 않는 알림과 다른 사용자의 알림을 각각 `404`, `403`으로
   구분한다.
5. **성능**: 전체 읽음은 알림을 한 건씩 조회·저장하지 않고 조건부 벌크 업데이트 한 번으로 처리한다. 안 읽은 개수는 목록을 불러오지 않고 `COUNT` 쿼리 한 번으로 처리한다.
6. **하위 호환성**: 기존 목록 조회 계약과 테이블 스키마를 변경하지 않는다. 새 API만 추가한다.

## 9. 결정 사항

- 단건 읽음과 전체 읽음은 멱등 동작으로 설계한다.
- 전체 읽음 응답에는 변경 건수를 반환하지 않는다. 프론트엔드는 처리 결과보다 읽음 상태
  자체에 관심이 있고, 변경 건수는 UI 계약에 필요하지 않다.
- `NOTIFICATION_001`, `NOTIFICATION_002`는 이번 이슈에서 추가한다. 이미 사용 중인
  `NOTIFICATION_003`의 번호는 변경하지 않는다.
