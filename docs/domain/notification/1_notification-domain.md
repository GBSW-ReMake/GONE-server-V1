# 알림(Notification) 도메인 — 기능 기획서 (초안)

> 관련 이슈: [#59 알림 도메인 — Notification 엔티티 + 공통 발송 모듈](https://github.com/GBSW-ReMake/GONE-server-V1/issues/59)
>
> 이 문서는 `outing`/`schoolcamp`처럼 알림 도메인 전체를 다루는 마스터 기획서다. 실제
> 구현은 이 문서에서 파생되는 하위 이슈 단위로 진행하며, 문서명은
> `docs/domain/notification/{이슈번호}-notification-{제목}.md` 규칙을 따른다(첫 파생 문서:
> [59-notification-core.md](./59-notification-core.md)). 각 이슈별 문서가 실제 최신
> 판단의 기준이고, 이 마스터 문서는 최초 설계 의도의 기록으로 남긴다([api-design.md](../../rules/api-design.md)
> "마스터 기획서 재검토" 원칙과 동일).

## 개요/목적
`outing`(외출증 승인/거절, 복귀 리마인더)과 `schoolcamp`(팀원 초대, 외출 미신청 리마인더)
양쪽 마스터 기획서 모두 "학생 기기에 알림을 보내야 한다"는 요구를 이미 갖고 있지만, 이
프로젝트에는 아직 어떤 형태의 알림 인프라도 없다. 이 문서는 그 공통 인프라를 독립된
도메인(`notification`)으로 분리해 설계한다.

> ⚠️ **설계 리뷰로 정정된 우선순위**: 최초 초안은 "FCM 실시간 푸시"를 메인으로 두고
> 인앱 알림 조회를 후순위로 미뤘으나, 검토 결과 뒤집었다. **서버가 알림을 저장하고
> 사용자가 목록으로 조회/읽음 처리하는 "인앱 알림함"이 메인이고, FCM 푸시(디바이스
> 토큰 등록/발송)는 그 알림이 왔다는 걸 앱을 안 켜도 바로 알 수 있게 해주는 실시간
> 보조 수단이다.** 이유는 두 가지다.
> 1. **알림을 놓쳤을 때의 최종 안전망이 인앱 알림함이다.** FCM 푸시는 기기 설정(알림
>    권한 거부), 네트워크 상태, 토큰 만료 등으로 도달이 보장되지 않는 "best-effort"
>    채널이다(위 "정책 가정" 참고). 반면 인앱 알림함은 앱만 열면 항상 정확한 이력을
>    볼 수 있다 — "알림을 못 받았다"는 문의가 들어왔을 때도 서버 DB만 보면 실제로
>    발송(저장)됐는지 바로 확인 가능하다.
> 2. **FCM(Firebase) 없이도 핵심 기능이 완결된다.** 인앱 알림함은 이 프로젝트 인프라
>    (DB)만으로 완성되고, Firebase 프로젝트 생성/서비스 계정 발급 같은 외부 종속성이
>    전혀 없어 먼저 출시해 검증하기 쉽다. FCM은 그 위에 "실시간성"만 더하는 순수
>    추가 계층이라, 나중에 붙여도(또는 일정상 밀려도) 인앱 알림함의 계약을 전혀
>    건드리지 않는다(아래 "단계 구분" 참고).

## 목차
1. [단계 구분](#단계-구분)
2. [용어 정리](#용어-정리)
3. [정책 가정](#정책-가정-확정-전--리뷰-시-조율-필요)
4. [권한 모델](#권한-모델)
5. [도메인 모델](#도메인-모델)
6. [엔드포인트](#엔드포인트)
7. [공통 발송 모듈](#공통-발송-모듈-notificationservice)
8. [도메인 연동 지점 (참고, 각 도메인 마스터 기획서가 원 출처)](#도메인-연동-지점-참고-각-도메인-마스터-기획서가-원-출처)
9. [에러 코드](#에러-코드-notificationerrorcode-신규-패키지-notification)
10. [공통 구현 고려사항](#공통-구현-고려사항)
11. [아직 결정 안 된 것](#아직-결정-안-된-것-리뷰-필요)

## 단계 구분
| 단계 | 내용 | 외부 종속성 | 상태 |
|---|---|---|---|
| **1단계 (메인, 착수 조각)** | `Notification` 엔티티 + 마이그레이션 + `NotificationService.send(...)`(저장만, 컨트롤러 없음) | 없음(이 프로젝트 DB만) | [59-notification-core.md](./59-notification-core.md), 착수 |
| **1단계 (메인, 나머지 조각)** | 목록 조회 + 읽음 처리(단건/모두) + 안 읽은 개수 4개 엔드포인트 | 없음 | 후속 이슈(미생성) |
| **2단계 (보조)** | FCM 디바이스 토큰 등록/삭제, `NotificationService.send(...)`에 FCM 발송 추가(1단계 시그니처 그대로, 내부 동작만 확장) | Firebase 프로젝트/서비스 계정 | 후속 이슈(미생성) |
| **3단계 이후** | `outing` 승인/거절·복귀 리마인더, `schoolcamp` 초대·리마인더가 `NotificationService.send(...)` 실제 호출 | 각 도메인 상태 | 각 도메인 후속 이슈(미생성) |

2단계가 1단계의 `send(userId, title, body)` 시그니처를 바꾸지 않는 이유는 아래 "공통 발송
모듈" 절 참고 — 호출하는 도메인(3단계) 입장에서는 몇 단계까지 구현됐는지 몰라도 된다.

## 용어 정리
- **알림(Notification)**: 서버가 특정 사용자에게 보낸 알림 1건의 저장 기록. 인앱
  알림함 화면의 목록 항목이 된다.
- **디바이스 토큰**: 클라이언트 앱이 FCM(Firebase Cloud Messaging)에 등록해 발급받는
  문자열. 서버가 이 값을 알아야 그 기기로 실시간 푸시를 보낼 수 있다(2단계).
- **공통 발송 모듈(`NotificationService`)**: 도메인 이벤트(외출증 승인 등)가 발생했을 때
  "이 사용자에게 이런 알림을 보내라"만 호출하면 되도록 저장/푸시 세부사항을 감추는 서비스.

## 정책 가정 (확정 전 — 리뷰 시 조율 필요)
- **알림은 항상 DB에 저장된다(메인 동작, 예외 없음).** FCM 디바이스 토큰이 없어도, FCM
  발송이 실패해도 `Notification` 저장 자체는 항상 성공해야 한다 — "실시간으로 못 받았어도
  나중에 앱 켜면 볼 수 있다"가 이 설계의 핵심 안전망이다.
- **FCM 발송은 best-effort 보조 수단이다.** 실패해도 예외를 던지지 않고 로그만 남기고
  삼킨다(swallow) — 호출하는 쪽(예: 외출증 승인 서비스 로직)의 핵심 트랜잭션이 "푸시
  발송 실패" 때문에 롤백되면 안 된다. 반면 **저장(1단계)이 실패하면 그건 진짜 예외로
  전파한다** — 알림 저장은 이 모듈의 핵심 책임이라 조용히 삼키면 안 된다(이 부분이
  `send(...)` 호출자 트랜잭션과 같은 트랜잭션으로 묶여도 괜찮은 이유이기도 하다 — 알림
  저장 실패가 승인 자체를 롤백시키는 건 오히려 타당하다).
- **사용자당 디바이스 토큰 1개만 저장한다(다중 기기 미지원, 2단계 MVP).** 새로 등록하면
  기존 값을 덮어쓴다 — `REFRESH_TOKEN`(Redis, `auth:refresh:{userId}`)이 이미 "최신 값으로
  교체(rotation)"하는 것과 같은 결의 단순화다.
- **FCM이 "이 토큰은 더 이상 유효하지 않다"고 응답하면(`UNREGISTERED`) 저장된 토큰을 즉시
  삭제한다(self-healing, 2단계).**
- **동기 호출로 설계한다(비동기/큐 도입 없음, YAGNI).** DB 저장은 어차피 호출자의 기존
  트랜잭션 안에서 동기로 일어나는 게 자연스럽다. FCM 발송(2단계)까지 같은 트랜잭션
  안에서 동기 호출하면 외부 HTTP 응답을 기다리는 동안 DB 락 보유 시간이 늘어질 수 있다 —
  이 트레이드오프는 2단계 구현 시 재검토 대상으로 남긴다.

## 권한 모델
- 인앱 알림 조회/읽음 처리, 디바이스 토큰 등록/삭제: 인증된 사용자 누구나(역할 무관,
  본인 것만 — 요청 파라미터로 대상 지정하지 않고 항상 `principal.userId()` 기준).
- `NotificationService`는 컨트롤러가 없는 순수 내부 서비스라 별도 인가가 필요 없다(호출하는
  쪽 도메인의 인가를 그대로 따른다).

## 도메인 모델

### `Notification` 엔티티 (`notification` 테이블, 신규, **1단계**)
- `id` — 내부 PK
- `user_id` — 수신자(`User` FK)
- `title` — 알림 제목
- `body` — 알림 본문
- `type` — 발송한 도메인이 자유롭게 붙이는 분류 태그(`VARCHAR(50)`, nullable, 예:
  `"OUTING_APPROVED"`). **알림 도메인은 이 값의 의미를 모른다** — 클라이언트가 아이콘
  선택/화면 이동(딥링크)에 쓸 수 있도록 문자열 그대로 전달만 한다. `outing`/`schoolcamp`
  같은 구체 도메인의 enum을 `notification` 패키지가 참조하면 역방향 의존이 생기므로
  일부러 자유 문자열로 둔다.
- `is_read` — 읽음 여부(`BOOLEAN`, 기본 `false`)
- `created_at` — 발송(저장) 시각

인덱스: `(user_id, created_at)` — 본인 알림을 최신순으로 조회하는 목록 API가 이 조합으로
정렬/필터하므로 필요.

### `DeviceToken` 엔티티 (`device_token` 테이블, 신규, **2단계**)
- `id` — 내부 PK
- `user_id` — 토큰을 등록한 사용자(`User` FK, **UNIQUE** — 사용자당 1행, 위 "정책 가정"
  참고)
- `fcm_token` — FCM 등록 토큰 문자열(`VARCHAR(255)`, 현재 FCM 토큰 형식 기준 여유 있는
  길이로 가정 — 실제 길이는 구현 시 재확인)
- `updated_at` — 마지막으로 등록/갱신된 시각(등록 시마다 갱신, `@UpdateTimestamp`)

### Flyway 마이그레이션
- `V9__add_notification.sql`(1단계) — `notification` 테이블 생성
- `V10__add_device_token.sql`(2단계) — `device_token` 테이블 생성 + `user_id` UNIQUE 제약

(정확한 V 번호는 구현 착수 시점에 재확인 — `outing`의 "번호는 사전 예약이 아니라 구현
순서대로 확정" 원칙과 동일.)

---

## 엔드포인트

### 1단계 (메인, 인앱 알림함)

#### 1. `GET /api/v1/notifications?page=&size=` — 본인 알림 목록 조회
**권한**: 인증된 사용자 누구나(본인 것만)

**요청**: `page`(선택, 기본 0), `size`(선택, 기본 20, 1~100 — 기존 `outing` 목록 조회
API와 동일한 페이지네이션 파라미터/제약 재사용)

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 501,
        "title": "외출증이 승인되었습니다",
        "body": "김선생님이 12:30 외출을 승인했어요.",
        "type": "OUTING_APPROVED",
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

**구현 로직**
1. `notificationRepository.findByUserIdOrderByCreatedAtDesc(...)`를 페이지네이션 조회
   (`outing`의 `PageResponse` 재사용 — 신규 페이지 응답 타입 만들지 않음)
2. DTO 변환 반환

**에러**: `page`/`size` 범위 벗어남 → `400`(`outing`의 `INVALID_PAGE_PARAMS`와 같은 성격,
번호는 구현 시 확정)

---

#### 2. `PATCH /api/v1/notifications/{id}/read` — 알림 읽음 처리
**권한**: 그 알림의 수신자 본인

**요청**: 바디 없음

**응답** (`200 OK`) — 바디 최소화
```json
{ "success": true, "data": null, "message": "알림을 읽음 처리했습니다.", "code": null }
```

**구현 로직**
1. `id`로 `Notification` 조회, 없으면 `404`
2. `principal.userId() == notification.getUserId()` 확인(소유권), 아니면 `403`
3. `is_read = true`로 갱신(이미 `true`여도 그대로 `200` — 멱등)

**에러**
- 본인 알림이 아님 → `403`
- 존재하지 않는 `id` → `404`

---

#### 3. `PATCH /api/v1/notifications/read-all` — 알림 모두 읽음 처리
**권한**: 인증된 사용자 누구나(본인 것만)

**요청**: 바디 없음

**응답** (`200 OK`)
```json
{ "success": true, "data": null, "message": "모든 알림을 읽음 처리했습니다.", "code": null }
```

**구현 로직**: `notificationRepository`에 벌크 갱신 쿼리(`@Modifying @Query("UPDATE
Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")`)를
추가해 한 번에 처리한다. 읽지 않은 알림을 하나씩 불러와 `save()`하는 방식(N+1)은 쓰지
않는다 — 2번 엔드포인트(단건 읽음)와 달리 이 엔드포인트는 애초에 "여러 건을 한 번에"가
목적이라 벌크 쿼리가 자연스럽다. 읽지 않은 알림이 원래 0건이어도 그대로 `200`(멱등).

**에러**: 없음(항상 성공 — 인증되지 않은 요청만 `401`).

---

#### 4. `GET /api/v1/notifications/unread-count` — 안 읽은 알림 개수 조회
**권한**: 인증된 사용자 누구나(본인 것만)

**요청**: 파라미터 없음

**응답** (`200 OK`)
```json
{ "success": true, "data": { "unreadCount": 3 }, "message": "안 읽은 알림 개수를 조회했습니다.", "code": null }
```

**구현 로직**: `notificationRepository.countByUserIdAndIsReadFalse(principal.userId())` →
단순 `COUNT` 쿼리 하나. 메인 화면(홈)에서 "새 알림 있음" 뱃지/점 표시에 쓰는 용도 —
가볍게 자주 호출될 수 있어(앱 진입/포그라운드 복귀 시마다) 목록 전체를 안 불러오고
개수만 반환하는 전용 엔드포인트로 분리했다.

**에러**: 없음(인증되지 않은 요청만 `401`).

---

### 2단계 (보조, FCM 푸시)

#### 5. `PUT /api/v1/notifications/device-token` — 디바이스 토큰 등록/갱신
**권한**: 인증된 사용자 누구나

**요청**
```json
{ "fcmToken": "dGhpcyBpcyBhIGZha2UgZmNtIHRva2Vu..." }
```
- `fcmToken`: 필수, not blank, 최대 255자

**응답** (`200 OK`)
```json
{ "success": true, "data": null, "message": "디바이스 토큰이 등록되었습니다.", "code": null }
```

**구현 로직**: `deviceTokenRepository.findByUserId(principal.userId())`로 기존 행 조회 →
있으면 `fcmToken`/`updatedAt` 갱신, 없으면 신규 생성(upsert). `PUT`을 쓰는 이유: "이
사용자의 현재 디바이스 토큰을 이 값으로 둔다"는 멱등 동작이라 `POST`보다 의미에 맞는다.

**에러**: `fcmToken` 빈 값/255자 초과 → `400` `COMMON_001`

---

#### 6. `DELETE /api/v1/notifications/device-token` — 디바이스 토큰 삭제
**권한**: 인증된 사용자 누구나(본인 것만)

**요청**: 바디 없음

**응답** (`200 OK`)
```json
{ "success": true, "data": null, "message": "디바이스 토큰이 삭제되었습니다.", "code": null }
```

**구현 로직**: `deviceTokenRepository.deleteByUserId(principal.userId())`. **멱등 처리** —
원래 없었어도 `404`가 아니라 `200`(로그아웃 시점에 "혹시 몰라 항상 호출"하는 식의 사용을
편하게 만든다).

---

## 공통 발송 모듈 (`NotificationService`)

### 1단계 구현
```java
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;

  public void send(Long userId, String title, String body, String type) {
    Notification notification = Notification.builder()
        .userId(userId).title(title).body(body).type(type).isRead(false).build();
    notificationRepository.save(notification); // 저장 실패는 그대로 예외 전파(정책 가정 참고)
  }
}
```
다른 도메인(`outing`, `schoolcamp`)은 이 빈을 주입받아 `notificationService.send(userId,
"외출증이 승인되었습니다", "...", "OUTING_APPROVED")`처럼 호출하기만 하면 된다.

### 2단계에서의 확장 (FCM 추가, 시그니처 불변)
```java
public void send(Long userId, String title, String body, String type) {
  Notification notification = Notification.builder()
      .userId(userId).title(title).body(body).type(type).isRead(false).build();
  notificationRepository.save(notification);

  deviceTokenRepository.findByUserId(userId).ifPresent(deviceToken -> {
    try {
      Message message = Message.builder()
          .setToken(deviceToken.getFcmToken())
          .setNotification(FcmNotification.builder().setTitle(title).setBody(body).build())
          .build();
      firebaseMessagingProvider.getObject().send(message);
    } catch (FirebaseMessagingException e) {
      if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
        deviceTokenRepository.deleteByUserId(userId);
      } else {
        log.error("FCM 발송 실패(userId={})", userId, e);
      }
    }
  });
}
```
**호출부(3단계, `outing`/`schoolcamp`)는 2단계가 언제 구현되든 코드를 전혀 고칠 필요가
없다** — `send(...)` 시그니처가 1단계부터 고정이고, 2단계는 그 내부 구현만 확장한다(
`api-design.md` 원칙 6 "하위 호환성"과 같은 결).

### 신규 의존성(2단계) — Firebase Admin SDK
`build.gradle`에 `implementation 'com.google.firebase:firebase-admin:9.x'`(정확한 최신
GA 버전은 2단계 구현 시 재확인).

### `FirebaseProperties`(2단계, `@ConfigurationProperties(prefix = "firebase")`)
`R2Properties`/`NeisProperties`와 같은 패턴 — 로컬 값은 `application-dev.yml`(git 미포함).
- `credentialsJson` — Firebase 서비스 계정 키 JSON 전체를 문자열 하나로(`@NotBlank`).
  파일 경로 방식 대신 문자열 방식을 택한 이유: 기존 시크릿(R2/JWT/NEIS)이 전부 "환경변수
  문자열 하나" 패턴이라 일관성을 유지한다.

> ⚠️ **`@Lazy`가 필요한 이유(2단계, CI `contextLoads()` 보호)**: `GoogleCredentials.
> fromStream(...)`은 빈 생성 시점에 문자열을 실제로 파싱해 RSA 개인키까지 구성하므로,
> R2/NEIS처럼 `dummy-...` 더미 문자열로는 컨텍스트 로딩 자체가 실패한다. `FirebaseApp`/
> `FirebaseMessaging` 빈을 `@Lazy`로, `NotificationService`는 `FirebaseMessaging`을
> `ObjectProvider<FirebaseMessaging>`로 받아 실제 발송 시점에만 꺼내 쓰게 하면, 3단계
> 호출부가 아직 없거나 CI처럼 더미 값만 있는 환경에서도 빈이 만들어지지 않아 안전하다.
> CI에는 `FIREBASE_CREDENTIALS_JSON: '{}'`(비어있지 않은 문자열이면 충분) 더미 값을
> 추가한다.

---

## 도메인 연동 지점 (참고, 각 도메인 마스터 기획서가 원 출처)
아래 두 지점(3단계)은 이 문서에서 새로 설계하지 않는다 — 이미 각 도메인 마스터 기획서에
"무엇을, 언제, 누구에게" 보낼지가 정의되어 있고, `NotificationService.send(...)`가
준비되면 그대로 호출하기만 하면 된다.

- **`outing` 도메인** — 승인/거절 결과 알림, 복귀 리마인더(위치 기반 + 시간 초과 감지),
  담당 선생님/선도부에게 미복귀 알림. 상세 로직:
  [`1_outing-domain.md`의 "복귀 리마인더 (백그라운드 스케줄러)"](../outing/1_outing-domain.md#복귀-리마인더-백그라운드-스케줄러)
- **`schoolcamp` 도메인** — 팀원으로 초대됐을 때 알림, 오늘 스쿨캠핑인데 점심 외출 미신청
  시 리마인더. 상세 로직:
  [`1_schoolcamp-domain.md`의 "외출 도메인 연동 — 리마인더"](../schoolcamp/1_schoolcamp-domain.md#외출-도메인-연동--리마인더)

## 에러 코드 (`NotificationErrorCode`, 신규 패키지 `notification`)
1단계부터 소유권 체크(읽음 처리 2번 엔드포인트)가 있어 전용 코드가 필요하다 — `outing`/
`user` 등 기존 도메인과 같은 `{DOMAIN}_NNN` 네이밍을 따른다.
1. `NOTIFICATION_001` (404) — 알림을 찾을 수 없습니다
2. `NOTIFICATION_002` (403) — 본인 알림만 읽음 처리할 수 있습니다
3. 목록 조회 페이지 파라미터 오류 코드(번호 미정, 400) — `outing`의
   `INVALID_PAGE_PARAMS`와 동일 성격, 구현 시 확정

모두 읽음 처리(3번 엔드포인트)/안 읽은 개수 조회(4번 엔드포인트)는 대상을 `id`로 특정하지
않고 항상 호출자 본인 기준이라 소유권 위반(403)/존재하지 않음(404) 케이스 자체가 없다 —
전용 코드 불필요.

2단계(디바이스 토큰)는 유일한 실패 케이스(빈 값 검증, 인증 실패)가 이미 있는
`CommonErrorCode`로 충분해 전용 코드가 필요 없다.

## 공통 구현 고려사항
- **저장(1단계)과 발송(2단계 FCM)의 실패 처리를 다르게 가져간다** — 위 "정책 가정" 참고.
  저장 실패는 전파, FCM 실패는 삼킴. 이 비대칭이 이 도메인의 핵심 설계 결정이다.
- **`send(...)` 시그니처는 1단계에서 확정하고 2단계는 내부만 확장**한다 — 위 "공통 발송
  모듈" 절 참고. 호출하는 도메인 코드가 단계와 무관하게 안정적으로 동작하게 하기 위함.
- **`@Lazy` + `ObjectProvider<FirebaseMessaging>`**는 2단계에서만 필요(1단계는 Firebase
  의존성 자체가 없어 해당 없음).
- **`type` 필드는 자유 문자열**이라 `notification` 패키지가 다른 도메인의 enum을 참조하지
  않는다 — 순수하게 저장/전달만 한다(위 "도메인 모델" 참고).
- **시크릿 관리**(2단계)는 R2/JWT/NEIS와 동일하게 `application-dev.yml`(git 미포함) + CI
  환경변수 더미 값 패턴을 따른다.

## 아직 결정 안 된 것 (리뷰 필요)
- **알림 목록 보존 기간**(무기한 저장 vs 일정 기간 후 삭제) — 개인정보/저장 공간 관점에서
  정책 필요.
- **다중 기기 지원 여부**(2단계) — 지금은 사용자당 토큰 1개. 여러 기기 동시 지원이
  필요해지면 `device_token`을 `user_id` UNIQUE에서 1:N으로 바꾸는 마이그레이션 필요.
- **`fcm_token` 컬럼 길이(255자 가정, 2단계)** — 실제 FCM 토큰 최대 길이를 구현 시 재확인.
- **Firebase 프로젝트/서비스 계정 실제 발급(2단계)** — 실제 Firebase 콘솔 작업은 2단계
  착수 시점에 진행(관리자 계정 필요 — 보스 확인 필요할 수 있음).
- **`outing`/`schoolcamp` 연동(3단계) 착수 순서** — 각 도메인 진행 상황에 맞춰 그때그때
  판단.
