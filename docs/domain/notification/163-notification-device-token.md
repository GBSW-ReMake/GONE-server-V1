# #163 FCM 디바이스 토큰 등록·삭제 기획서

> 관련 Issue: [#163 FCM 디바이스 토큰 등록·삭제 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/163)
>
> 선행 기능: [#59 알림 공통 발송 모듈](./59-notification-core.md),
> [#119 알림 목록 조회](./119-notification-list.md),
> [#128 알림 읽음 처리](./128-notification-read.md)
>
> 마스터 기획서: [1_notification-domain.md](./1_notification-domain.md)

## 1. 배경 및 목적

인앱 알림함은 서버 DB에 저장된 알림 이력을 사용자가 확인하는 핵심 수단이다. 그러나 앱이
실행 중이지 않을 때도 새 알림을 즉시 알리려면, 서버가 사용자의 기기를 식별할 수 있는 FCM
디바이스 토큰을 알아야 한다.

이번 이슈는 알림 도메인 2단계의 첫 조각으로, **클라이언트 앱이** Firebase에서 발급받은
현재 FCM 토큰을 인증된 사용자의 서버 계정에 등록·갱신하거나 삭제할 수 있게 한다. 사용자가
토큰 문자열을 직접 입력하는 화면은 만들지 않는다. 실제 Firebase Admin SDK 호출과 푸시 발송은
다음 이슈로 분리한다. 따라서 이번 단계가 끝나도 `NotificationService.send(...)`의 기존 DB
저장 동작은 변하지 않는다.

MVP에서는 사용자당 최신 디바이스 토큰 하나만 보관한다. 같은 사용자가 새 토큰을 등록하면
기존 토큰을 교체한다. 이는 앱 재설치, 기기 변경, FCM 토큰 갱신이 일어나도 서버가 가장 최근
토큰만 유지하게 하기 위한 정책이다.

## 2. 범위

### 포함

- `DeviceToken` 엔티티와 `device_token` 테이블 생성
- `PUT /api/v1/notifications/device-token` 토큰 등록·갱신 API
- `DELETE /api/v1/notifications/device-token` 토큰 삭제 API
- 인증·입력 검증, 본인 토큰 한정 처리
- 단위 테스트, Controller 테스트, DB 통합 테스트

### 제외

- Firebase Admin SDK 의존성·설정·서비스 계정 키 추가
- 실제 FCM 푸시 발송 및 만료 토큰(`UNREGISTERED`) 자동 삭제
- 다중 기기 토큰 보관
- 알림 목록·읽음 처리 API 변경
- 외출·스쿨캠핑·상벌점 도메인의 알림 발송 연결
- 딥링크 연결

## 3. 정책 및 권한

- 두 API는 인증된 사용자만 호출할 수 있다.
- Controller는 `@AuthenticationPrincipal UserPrincipal`의 `userId`만 사용한다. 요청 경로,
  쿼리, 본문으로 대상 사용자 ID를 받지 않는다.
- 사용자당 `DeviceToken` 행은 최대 하나다. DB의 `user_id` UNIQUE 제약으로도 보장한다.
- `PUT`은 멱등적으로 동작한다. 같은 토큰을 반복 등록해도 최종 저장값은 동일하다.
- `DELETE`는 토큰이 원래 없어도 `200 OK`를 반환한다. 클라이언트가 로그아웃·연결 해제 때
  현재 상태를 별도 조회하지 않고 안전하게 호출할 수 있게 하기 위함이다.
- FCM 토큰은 인증 정보 자체가 아니라 기기 식별 및 푸시 발송 대상 정보다. 다만 외부에
  노출하지 않으며, 응답 본문에도 반환하지 않는다.

## 4. 데이터 모델 및 마이그레이션

### 4.1 `DeviceToken` 엔티티

| 필드 | DB 컬럼 | 제약 | 설명 |
|---|---|---|---|
| `id` | `id` | PK, auto increment | 내부 식별자 |
| `user` | `user_id` | NOT NULL, FK, UNIQUE | 토큰 소유 사용자 |
| `fcmToken` | `fcm_token` | NOT NULL, VARCHAR(255) | 현재 FCM 등록 토큰 |
| `updatedAt` | `updated_at` | NOT NULL | 등록 또는 갱신 시각 |

- `DeviceToken.user`는 `User`를 `LAZY`로 참조한다.
- 엔티티에 `updateFcmToken(String fcmToken)` 메서드를 두고, Service가 필드에 직접 대입하지
  않는다.
- `updatedAt`은 기존 `ConductRequest`·`ConductRecord`와 동일하게 애플리케이션에서
  `@UpdateTimestamp`로 관리한다. 마이그레이션의 `DEFAULT CURRENT_TIMESTAMP ON UPDATE`는 DB
  수준의 기본값이며, 엔티티 메서드는 시각을 직접 변경하지 않는다.

### 4.2 Flyway 마이그레이션

- 파일명은 구현·커밋 시점의 KST 타임스탬프 규칙을 따른다.
  - 예시: `V20260910123000__add_device_token.sql`
  - 실제 파일명은 미리 예약하지 않는다.
- `device_token` 테이블을 생성한다.
- `user_id`는 `user(id)` 외래 키와 UNIQUE 제약을 가진다.
- 삭제·갱신은 `WHERE user_id = ?` 조건 하나로 수행되므로, UNIQUE 제약이 만드는 인덱스를
  그대로 사용한다. 별도 인덱스는 추가하지 않는다.

## 5. API 명세

### 5.1 `PUT /api/v1/notifications/device-token`

로그인된 클라이언트 앱이 현재 기기의 FCM 토큰을 서버에 등록하거나 기존 토큰을 갱신한다.

#### 요청

```http
PUT /api/v1/notifications/device-token
Authorization: Bearer {accessToken}
Content-Type: application/json
```

```json
{
  "fcmToken": "dGhpcyBpcyBhIGZha2UtZmNtLXRva2Vu"
}
```

| 필드 | 필수 | 제약 | 설명 |
|---|---|---|---|
| `fcmToken` | 예 | 공백 불가, 최대 255자 | 현재 기기의 FCM 등록 토큰 |

#### 성공 응답

HTTP 상태 코드: `200 OK`

```json
{
  "success": true,
  "data": null,
  "message": "디바이스 토큰이 등록되었습니다.",
  "code": null
}
```

기존 행이 있으면 토큰과 갱신 시각을 변경하고, 없으면 새 행을 만든다. 두 경우 모두 같은
응답을 반환한다. 클라이언트는 등록과 갱신을 구분할 필요가 없다.

#### 오류 응답

| 상황 | HTTP 상태 | 에러 코드 |
|---|---:|---|
| 인증 정보가 없거나 유효하지 않음 | `401` | `COMMON_002` |
| `fcmToken`이 없거나 공백임 | `400` | `COMMON_001` |
| `fcmToken`이 255자를 초과함 | `400` | `COMMON_001` |
| 요청 본문 JSON 형식이 올바르지 않음 | `400` | `COMMON_001` |

유효하지 않은 토큰 요청 예시:

```json
{
  "success": false,
  "data": null,
  "message": "fcmToken: FCM 토큰은 비어 있을 수 없습니다.",
  "code": "COMMON_001"
}
```

인증 실패 응답 예시(두 API 공통):

```json
{
  "success": false,
  "data": null,
  "message": "인증이 필요합니다.",
  "code": "COMMON_002"
}
```

#### 처리 순서

1. Security가 Access Token을 검증하고 현재 사용자 ID를 `UserPrincipal`에 넣는다.
2. Controller가 요청 본문의 `fcmToken`을 검증하고 사용자 ID와 함께 Service에 전달한다.
3. Service가 `findByIdForUpdate(userId)`로 사용자 행을 잠근다. 사용자가 없으면 stale JWT로
   판단해 `401 COMMON_002`를 반환한다.
4. Service가 `findByUserId(userId)`로 기존 행을 조회한다.
5. 기존 행이 있으면 엔티티 메서드로 토큰을 갱신한다.
6. 기존 행이 없으면 조회한 사용자와 토큰으로 새 `DeviceToken`을 저장한다.

### 5.2 `DELETE /api/v1/notifications/device-token`

클라이언트 앱이 자신의 현재 디바이스 토큰을 삭제한다. 로그아웃 또는 앱의 푸시 연결 해제 시
자동으로 호출한다.

#### 요청

```http
DELETE /api/v1/notifications/device-token
Authorization: Bearer {accessToken}
```

요청 본문과 파라미터는 없다.

#### 성공 응답

HTTP 상태 코드: `200 OK`

```json
{
  "success": true,
  "data": null,
  "message": "디바이스 토큰이 삭제되었습니다.",
  "code": null
}
```

기존 토큰이 없는 경우에도 같은 응답을 반환한다.

#### 오류 응답

| 상황 | HTTP 상태 | 에러 코드 |
|---|---:|---|
| 인증 정보가 없거나 유효하지 않음 | `401` | `COMMON_002` |

#### 처리 순서

1. Security가 현재 사용자 ID를 `UserPrincipal`에 넣는다.
2. Controller가 사용자 ID만 Service에 전달한다.
3. Service가 `findByIdForUpdate(userId)`로 사용자 행을 잠근다. 이 락은 같은 사용자의 등록과
   삭제가 동시에 실행되지 않게 한다.
4. Service가 `deleteByUserId(userId)`를 실행한다.
5. 삭제 건수가 0이어도 예외 없이 성공 응답을 반환한다.

## 6. 구현 구조

### Controller

- 기존 `NotificationController`에 `PUT`, `DELETE` 메서드 두 개를 추가한다.
- 두 메서드에 `@PreAuthorize("isAuthenticated()")`를 적용한다.
- 등록 요청은 `RegisterDeviceTokenRequest` DTO로 받고 `@Valid`를 적용한다.
- Controller는 토큰 조회·갱신·삭제를 직접 수행하지 않고 Service 호출과 `ApiResponse.success(...)`
  반환만 담당한다.

### Service

- `registerDeviceToken(Long userId, String fcmToken)`은 트랜잭션 안에서 사용자 행에 배타적
  락을 건 뒤 기존 토큰을 조회해 갱신 또는 생성한다.
- `deleteDeviceToken(Long userId)`도 같은 사용자 행 잠금을 획득한 뒤 사용자 ID 조건으로
  삭제한다.
- 사용자 ID는 Controller의 인증 principal에서만 오므로, 외부 입력을 신뢰하지 않는다.
- 향후 푸시 발송 이슈에서 이 Service 또는 별도 FCM 전송 컴포넌트가 `DeviceTokenRepository`를
  조회해 사용한다.

### Repository

- `Optional<DeviceToken> findByUserId(Long userId)`를 제공한다.
- `int deleteByUserId(Long userId)` 또는 동등한 단일 DELETE 쿼리를 제공한다.
- 사용자당 한 행이라는 제약이 있으므로 목록 조회 API는 만들지 않는다.

### DTO

```java
public record RegisterDeviceTokenRequest(
    @NotBlank(message = "FCM 토큰은 비어 있을 수 없습니다.")
    @Size(max = 255, message = "FCM 토큰은 255자 이하여야 합니다.")
    String fcmToken
) {
}
```

응답은 토큰 값을 포함하지 않는 `ApiResponse<Void>`를 사용한다.

## 7. 테스트 계획

### Service 단위 테스트

- 토큰이 없으면 새 `DeviceToken`을 저장한다.
- 기존 토큰이 있으면 새 엔티티를 만들지 않고 토큰을 갱신한다.
- 삭제 대상이 있으면 삭제 Repository를 호출한다.
- 삭제 대상이 없어도 예외가 발생하지 않는다.

### Controller 테스트

- 유효한 요청은 `200`과 등록 성공 메시지를 반환한다.
- 빈 토큰·공백 토큰·256자 토큰은 `400 COMMON_001`을 반환한다.
- 인증되지 않은 요청은 `401 COMMON_002`를 반환한다.
- 삭제 요청은 인증된 사용자 ID만 Service에 전달한다.

### DB 통합 테스트

- 같은 클라이언트가 토큰 등록 요청을 두 번 보내도 `device_token` 행은 하나만 존재하고 최신
  토큰으로 갱신된다.
- 서로 다른 사용자는 서로의 토큰에 영향을 주지 않는다.
- 토큰을 삭제한 뒤 행이 존재하지 않는다.
- 없는 토큰 삭제 요청도 성공한다.
- Flyway가 새 마이그레이션을 적용한 상태에서 기존 알림 도메인 테스트가 통과한다.

## 8. 리스크 및 고려사항

- **동시 등록·삭제**: 같은 사용자의 요청은 `UserRepository.findByIdForUpdate()`의 배타적 락으로
  직렬화한다. 먼저 시작한 트랜잭션이 끝난 뒤 다음 요청이 최신 토큰 상태를 조회하므로,
  동시 등록에서도 `user_id` UNIQUE 충돌을 사용자에게 노출하지 않는다.
- **토큰의 실제 유효성**: 이 단계에서는 문자열의 비어 있음과 길이만 검증한다. 토큰이 실제
  FCM에서 유효한지는 다음 푸시 발송 단계의 Firebase 응답으로만 알 수 있다.
- **FCM 실패와 분리**: 이번 이슈는 외부 Firebase 의존성이 없으므로, 로컬·CI에서 Firebase
  서비스 계정 키가 필요하지 않다. 다음 이슈에서 FCM 발송 실패가 알림 DB 저장 또는 호출
  도메인 트랜잭션을 롤백하지 않도록 별도 설계한다.
- **하위 호환성**: 기존 알림 API의 경로·응답 필드는 변경하지 않는다. 새 경로 두 개만
  추가하므로 기존 클라이언트와 충돌하지 않는다.
- **다중 기기**: 사용자당 하나만 보관하는 MVP 정책이다. 다중 기기 지원이 필요해지면
  `user_id` UNIQUE 제약을 제거하고 기기 식별자 등을 추가하는 별도 마이그레이션·이슈가
  필요하다.
