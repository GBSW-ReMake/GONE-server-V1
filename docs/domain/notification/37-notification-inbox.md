# #37 알림 인프라 도입 (1단계) — 인앱 알림함 저장/조회/읽음 처리

관련 이슈: [#37 알림 시스템 도입 (외출증 승인/거절, 스쿨캠핑 리마인더 등)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/37)
전체 도메인 마스터 기획서: [1_notification-domain.md](./1_notification-domain.md) — 이
문서는 그중 **"1단계(메인): 인앱 알림함"** 만 좁힌 것. FCM 푸시(2단계)와 `outing`/
`schoolcamp` 실제 연동(3단계)은 이번 이슈 범위 밖 — 마스터 기획서의 "단계 구분" 절 참고.

## 개요/목적
알림 도메인의 메인은 "서버가 알림을 저장하고 사용자가 목록으로 조회/읽음 처리하는 인앱
알림함"이다(FCM 실시간 푸시는 그 위에 얹는 보조 수단 — 마스터 기획서 "개요/목적"의
"설계 리뷰로 정정된 우선순위" 참고). 이 이슈는 그 메인 기능만 구현한다:

1. `Notification` 엔티티 + 저장 담당 `NotificationService.send(...)`
2. 본인 알림 목록 조회 API(페이지네이션)
3. 알림 읽음 처리 API(단건 + 모두 읽음 일괄)
4. 안 읽은 알림 개수 조회 API(메인 화면 뱃지 표시용)

Firebase/FCM 관련 작업은 전혀 포함하지 않는다 — 이 이슈는 외부 종속성 없이 DB만으로
완결된다(마스터 기획서 "개요/목적"의 근거 2 참고). 아직 이 `send(...)`를 실제로 호출하는
곳(외출증 승인 등)은 없다 — 3단계 후속 이슈에서 연결한다.

## 엔드포인트

### 1. `GET /api/v1/notifications?page=&size=` — 본인 알림 목록 조회
**권한**: 인증된 사용자 누구나(본인 것만, `principal.userId()` 기준 — 요청 파라미터로
대상 지정 불가)

**요청**: `page`(선택, 기본 0), `size`(선택, 기본 20, 1~100)

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 501,
        "title": "테스트 알림 제목",
        "body": "테스트 알림 본문입니다.",
        "type": "TEST",
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
1. `page`/`size` 검증(`outing`의 `validatePageParams`와 동일 규칙: `page >= 0`,
   `1 <= size <= 100`)
2. `notificationRepository.findByUserIdOrderByCreatedAtDesc(principal.userId())` 조회
3. `PageResponse.of(...)`로 페이지 잘라 DTO 변환 반환(`outing`의 `getMyRequests`와 동일
   패턴 — 신규 페이지 응답 타입 만들지 않고 기존 `PageResponse` 재사용)

**에러**
- `page < 0` 또는 `size`가 1~100 범위 밖 → `400` `NOTIFICATION_003`(가칭, 아래 "에러 코드"
  참고)

---

### 2. `PATCH /api/v1/notifications/{id}/read` — 알림 읽음 처리
**권한**: 그 알림의 수신자 본인

**요청**: 바디 없음

**응답** (`200 OK`)
```json
{ "success": true, "data": null, "message": "알림을 읽음 처리했습니다.", "code": null }
```

**구현 로직**
1. `id`로 `Notification` 조회, 없으면 `404`
2. `principal.userId() == notification.getUserId()` 확인(소유권), 아니면 `403`
3. `is_read = true`로 갱신 후 저장(이미 `true`여도 그대로 `200` 반환 — 멱등, 재요청이
   실패로 취급될 이유가 없다)

**에러**
- 본인 알림이 아님 → `403` `NOTIFICATION_002`
- 존재하지 않는 `id` → `404` `NOTIFICATION_001`

---

### 3. `PATCH /api/v1/notifications/read-all` — 알림 모두 읽음 처리
**권한**: 인증된 사용자 누구나(본인 것만)

**요청**: 바디 없음

**응답** (`200 OK`)
```json
{ "success": true, "data": null, "message": "모든 알림을 읽음 처리했습니다.", "code": null }
```

**구현 로직**: `NotificationRepository`에 벌크 갱신 쿼리(`@Modifying @Query("UPDATE
Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")`)를
추가해 한 번에 처리한다 — 읽지 않은 알림을 하나씩 불러와 `save()`하는 N+1 방식은 쓰지
않는다. 읽지 않은 알림이 원래 0건이어도 그대로 `200`(멱등).

**에러**: 없음(인증되지 않은 요청만 `401`).

---

### 4. `GET /api/v1/notifications/unread-count` — 안 읽은 알림 개수 조회
**권한**: 인증된 사용자 누구나(본인 것만)

**요청**: 파라미터 없음

**응답** (`200 OK`)
```json
{ "success": true, "data": { "unreadCount": 3 }, "message": "안 읽은 알림 개수를 조회했습니다.", "code": null }
```

**구현 로직**: `notificationRepository.countByUserIdAndIsReadFalse(principal.userId())` →
단순 `COUNT` 쿼리 하나. 메인 화면(홈)에서 "새 알림 있음" 뱃지/점 표시에 쓰는 용도 —
앱 진입/포그라운드 복귀 시마다 자주 호출될 수 있어 목록 전체가 아니라 개수만 반환하는
전용 엔드포인트로 분리했다.

**에러**: 없음(인증되지 않은 요청만 `401`).

---

## 공통 발송 모듈 — `NotificationService` (1단계)
```java
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;

  public void send(Long userId, String title, String body, String type) {
    Notification notification = Notification.builder()
        .userId(userId)
        .title(title)
        .body(body)
        .type(type)
        .isRead(false)
        .build();
    notificationRepository.save(notification);
  }
}
```
- 저장 실패는 그대로 예외 전파한다(삼키지 않음) — 알림 저장은 이 모듈의 핵심 책임이라,
  호출자(향후 `outing`/`schoolcamp`) 트랜잭션과 함께 롤백되는 게 오히려 맞는 동작이다(
  마스터 기획서 "정책 가정" 참고).
- `type`은 자유 문자열(예: `"OUTING_APPROVED"`) — `notification` 패키지가 다른 도메인의
  enum을 참조하지 않기 위해 의도적으로 비타입 문자열로 둔다.
- **이번 이슈에는 이 메서드를 실제로 호출하는 곳이 없다** — 단위 테스트로만 동작을
  검증하고, `outing`/`schoolcamp` 연동은 3단계 후속 이슈에서 진행한다. 컨트롤러 없는
  서비스만 먼저 만들어두는 게 이상해 보일 수 있으나, 마스터 기획서가 이미 "저장 →
  FCM(2단계) → 실제 호출(3단계)" 순서로 계획을 명시해뒀다.

## 데이터 모델 변경
### `Notification` 엔티티 (`notification` 테이블, 신규)
- `id` — 내부 PK
- `user_id` — 수신자(`User` FK)
- `title` — 알림 제목(`VARCHAR(100)`, 가정)
- `body` — 알림 본문(`VARCHAR(500)`, 가정)
- `type` — 발송 도메인이 붙이는 분류 태그(`VARCHAR(50)`, nullable)
- `is_read` — 읽음 여부(`BOOLEAN NOT NULL DEFAULT false`)
- `created_at` — 발송(저장) 시각(`@CreationTimestamp`)

인덱스: `(user_id, created_at)` — 목록 조회가 이 조합으로 정렬/필터.

### Flyway 마이그레이션
`V9__add_notification.sql`(신규) — `notification` 테이블 생성.

## 영향 받는 기존 코드
- `SecurityConfig`: `.requestMatchers("/api/v1/notifications/**").authenticated()` 추가.
- 신규 패키지 `notification`(`controller`/`dto`/`entity`/`repository`/`service`/
  `exception`) — 이 프로젝트에 새 도메인 패키지가 추가되는 사례(`outing`/`schoolcamp`와
  같은 하위 폴더 구조).
- 신규 테스트: `NotificationServiceTest`(저장 성공, `type` null 허용), `NotificationController
  Test`(목록 조회 페이지네이션, 읽음 처리 성공/403/404, 모두 읽음 처리, 안 읽은 개수 조회,
  페이지 파라미터 검증 400).

## 에러 코드 (`NotificationErrorCode`, 신규 패키지 `notification`)
1. `NOTIFICATION_001` (404) — 알림을 찾을 수 없습니다
2. `NOTIFICATION_002` (403) — 본인 알림만 처리할 수 있습니다
3. `NOTIFICATION_003` (400) — 페이지 조회 조건이 올바르지 않습니다(`outing`의
   `INVALID_PAGE_PARAMS`와 동일 성격 — 별도 도메인이라 코드는 새로 받는다)

모두 읽음 처리(3번)/안 읽은 개수 조회(4번)는 대상을 `id`로 특정하지 않고 항상 호출자
본인 기준이라 소유권 위반(403)/존재하지 않음(404) 케이스 자체가 없다 — 전용 코드 불필요.

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 저장/조회/읽음 처리만 책임진다. FCM 발송, 실제 도메인 연동은
   후속 이슈로 분리했다.
2. **빠르게 시작하기**: 요청/응답 예시 위에 명시.
3. **일관성**: `ApiResponse`/`PageResponse`/`CustomException` 패턴을 `outing`과 동일하게
   재사용. 페이지네이션 파라미터 이름/기본값/제약도 `outing`과 동일하게 맞췄다.
4. **의미 있는 오류**: 소유권 위반(403)과 존재하지 않음(404)을 분리 — 원인이 다르므로
   `api-design.md` 원칙 4에 부합.
5. **확장성/성능**: 목록 조회에 페이지네이션 적용(무제한 조회 금지). `(user_id,
   created_at)` 인덱스로 목록 조회 성능 확보.
6. **하위 호환성**: 완전히 새로운 도메인이라 기존 API에 영향 없음. `NotificationService.
   send(...)` 시그니처는 2단계(FCM)에서도 그대로 유지할 계획이라(마스터 기획서 참고)
   향후 확장에도 호출자 영향이 없다.

## 리스크 및 고려사항
- **아직 아무도 호출하지 않는 서비스를 먼저 만드는 것에 대한 우려**: `send(...)`의 실제
  호출부가 이번 이슈에 없어, 이 자체만으로는 사용자가 체감할 기능이 없다(테스트로만
  검증). 다만 (1) 다음 이슈(외출증 알림 연동 등)에서 바로 재사용 가능한 상태로 만들어두는
  것이 목적이고, (2) 조회/읽음 API는 `Notification` 데이터가 있어야 QA 가능하므로 QA
  시점에는 테스트 데이터를 직접 INSERT하거나 별도 테스트용 임시 엔드포인트/단위 테스트로
  검증한다(아래 "테스트 방법" 참고).
- **알림 목록 보존 기간 미정** — 마스터 기획서 "아직 결정 안 된 것"에 남김, 이번 이슈에서
  삭제/보존 정책을 구현하지 않는다(무기한 저장).
- **모두 읽음 처리의 벌크 쿼리 vs 단건 반복**: 읽지 않은 알림 수가 아주 많은 사용자가
  있다면 벌크 `UPDATE` 한 번이 단건 반복보다 항상 유리하다 — 별도 트레이드오프 없음.

## 테스트 방법
이번 이슈는 `send(...)`의 실제 호출부가 없어 Postman으로 "알림이 생성되는 흐름"까지는
검증할 수 없다. 아래 순서로 진행한다.

1. 단위/통합 테스트(`NotificationServiceTest`)로 `send(...)` 저장 동작 검증(정상 저장,
   `type` null 허용 등)
2. 로컬 DB에 테스트 알림을 여러 건 직접 `INSERT`한 뒤, Postman으로 아래를 검증
   - 목록 조회(정상/페이지 파라미터 400)
   - 단건 읽음 처리(정상/403/404)
   - 모두 읽음 처리 → 이후 목록 조회 시 전부 `isRead: true`로 바뀌었는지 확인
   - 안 읽은 개수 조회 → 읽음 처리 전후로 값이 정확히 줄어드는지 확인
3. `./gradlew build`, `./gradlew test`, `./gradlew checkstyleMain` 로컬 통과 확인 + CI
   통과 확인
