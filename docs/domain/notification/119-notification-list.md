# 알림 목록 조회 기획서

> 관련 Issue: [#119 알림 목록 조회 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/119)
>
> 마스터 기획서: [`1_notification-domain.md`](./1_notification-domain.md)
>
> 기능정의서: `알림 목록 조회`

## 1. 배경 및 목적

사용자가 본인이 받은 알림을 인앱 알림함에서 최신순으로 확인할 수 있도록 목록 조회 API를
구현한다.

FCM 푸시는 기기 설정, 네트워크, 토큰 만료 등의 이유로 전달이 보장되지 않는다. 따라서
알림은 DB에 저장된 이력을 조회하는 인앱 알림함을 기준으로 제공하며, 이번 기능은 Firebase나
APNs에 의존하지 않는다.

## 2. 범위

### 포함

- `GET /api/v1/notifications` 엔드포인트
- Access Token의 `principal.userId()`를 기준으로 본인 알림만 조회
- DB 페이지네이션
- 최신 알림 우선 정렬
- `Notification` 정보를 응답 DTO로 변환
- 페이지 파라미터 검증
- 정상 및 예외 테스트

### 제외

- 단건 알림 읽음 처리
- 전체 알림 읽음 처리
- 안 읽은 알림 개수 조회
- FCM 디바이스 토큰 등록·삭제 및 푸시 발송
- 외출·스쿨캠핑·상벌점 도메인의 실제 알림 발송 연동
- 딥링크 연결

위 기능은 각각 별도 Issue에서 진행한다.

## 3. 권한 및 보안

- 인증된 사용자만 호출할 수 있다.
- 역할과 관계없이 인증된 사용자가 호출할 수 있다.
- 조회 대상 사용자 ID를 요청 파라미터로 받지 않는다.
- 항상 `UserPrincipal.userId()`를 사용해 본인 알림만 조회한다.
- 다른 사용자의 알림이 응답에 포함되지 않도록 Repository 조회 조건에서 사용자 ID를
  제한한다.

## 4. API 명세

### 4.1 요청

```http
GET /api/v1/notifications?page=0&size=20
Authorization: Bearer {accessToken}
```

| 파라미터 | 타입 | 필수 | 기본값 | 제약 |
|---|---|---:|---:|---|
| `page` | `int` | 아니오 | `0` | `0` 이상 |
| `size` | `int` | 아니오 | `20` | `1` 이상 `100` 이하 |

### 4.2 성공 응답

HTTP 상태 코드: `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 501,
        "title": "외출증이 승인되었습니다",
        "body": "김선생님이 12:30 외출을 승인했어요.",
        "type": "OUTING",
        "isRead": false,
        "createdAt": "2026-08-14T12:29:10"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  },
  "message": "알림 목록을 조회했습니다.",
  "code": null
}
```

응답은 프로젝트 공통 `ApiResponse<PageResponse<NotificationResponse>>` 형식을 사용한다.

### 4.3 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | `Long` | 알림 식별자 |
| `title` | `String` | 알림 제목 |
| `body` | `String` | 알림 본문 |
| `type` | `NotificationType` 또는 `null` | 도메인별 이모지 매핑용 타입 |
| `isRead` | `boolean` | 읽음 여부 |
| `createdAt` | `LocalDateTime` | 알림 저장 시각 |

`type`은 다음 Enum 값을 그대로 응답한다.

- `OUTING`
- `SCHOOLCAMP`
- `MERIT`
- `DEMERIT`
- `null`

### 4.4 오류 응답

| 상황 | HTTP 상태 | 에러 코드 |
|---|---:|---|
| 인증 정보가 없거나 유효하지 않음 | `401` | `COMMON_002` |
| `page < 0` 또는 `size`가 `1~100` 범위를 벗어남 | `400` | `NOTIFICATION_003` |

페이지 범위를 넘어선 페이지를 요청한 경우에는 오류로 처리하지 않고 빈 `content`와 정상적인
페이지 메타데이터를 반환한다. 페이지네이션에서 마지막 페이지 다음 요청은 정상적인 클라이언트
동작일 수 있기 때문이다.

## 5. 조회 및 정렬 정책

Repository에 사용자 ID와 `Pageable`을 전달하는 DB 페이지네이션 메서드를 추가한다.

```java
Page<Notification> findByUserId(Long userId, Pageable pageable);
```

호출부에서 다음 정렬을 지정한다.

```text
createdAt DESC, id DESC
```

알림 저장 시각이 같은 데이터가 있을 수 있으므로 `id DESC`를 보조 정렬 키로 사용해 페이지
경계에서 순서가 흔들리는 것을 방지한다.

조회 결과는 `PageResponse.of(page)`로 변환한다. 이미 DB에서 페이지 단위로 조회한 결과를
다시 메모리에서 자르거나 전체 알림을 조회하지 않는다.

## 6. 구현 구조

### Controller

- `@RestController`와 `/api/v1/notifications` 경로 사용
- `@AuthenticationPrincipal UserPrincipal`에서 사용자 ID 추출
- `page`, `size` 기본값 적용
- Service 호출 후 `ApiResponse.success(...)` 반환
- 컨트롤러에서 DB 조회나 소유권 판단을 하지 않음

### Service

- 페이지 파라미터 검증
- `PageRequest.of(page, size, sort)` 생성
- `principal.userId()`를 Repository에 전달
- `Notification`을 `NotificationResponse`로 변환
- `PageResponse.of(Page<T>)` 반환

### Repository

- 사용자 ID 조건으로 알림을 제한
- `Pageable`을 받아 DB에서 페이지네이션
- 정렬은 호출부의 `Pageable`을 사용

### DTO

조회 응답 전용 `NotificationResponse`를 추가한다. 엔티티를 API 응답으로 직접 노출하지
않으며, `Notification.type`의 Enum과 `createdAt`을 응답에 포함한다.

## 7. 테스트 계획

### Service 단위 테스트

- 정상적으로 본인 알림 페이지를 조회한다.
- 사용자 ID가 Repository 조회 조건에 전달되는지 확인한다.
- 최신순 정렬을 포함한 `Pageable`이 전달되는지 확인한다.
- `Notification`이 `NotificationResponse`로 올바르게 변환된다.
- `type`이 `null`인 알림도 정상 변환된다.
- `page < 0`이면 `NOTIFICATION_003`을 발생시킨다.
- `size < 1` 또는 `size > 100`이면 `NOTIFICATION_003`을 발생시킨다.

### Controller/API 테스트

- 인증된 사용자가 `200 OK`와 페이지 응답을 받는다.
- `page`, `size`를 생략하면 각각 `0`, `20`이 적용된다.
- 잘못된 페이지 파라미터에 `400`과 `NOTIFICATION_003`이 반환된다.
- 인증되지 않은 요청은 `401`과 `COMMON_002`로 처리된다.
- 응답에 `id`, `title`, `body`, `type`, `isRead`, `createdAt`이 포함된다.

## 8. 검증 방법

- `./gradlew test`
- `./gradlew checkstyleMain`
- `./gradlew checkstyleTest`
- `./gradlew build`
- 테스트용 알림 데이터를 DB에 준비한 뒤 Postman으로 다음을 확인한다.
  - 기본 페이지 조회
  - 사용자별 데이터 격리
  - 최신순 정렬
  - `page`, `size` 경계값
  - 잘못된 페이지 파라미터
  - 인증되지 않은 요청

## 9. 결정 사항 및 주의점

- Firebase/APNs 인증 정보는 이번 기능에 필요하지 않다.
- 알림 타입은 이벤트 종류가 아니라 도메인 분류이므로 조회 API에서 Enum 값을 그대로
  전달한다.
- 요청으로 사용자 ID를 받지 않아 IDOR를 방지한다.
- 전체 목록을 메모리에 올리는 방식 대신 DB 페이지네이션을 사용한다.
- 읽음 처리와 FCM 발송은 이번 브랜치의 범위를 벗어난다.
