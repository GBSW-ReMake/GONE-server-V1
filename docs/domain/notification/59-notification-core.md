# #59 알림 도메인 — Notification 엔티티 + 공통 발송 모듈

관련 이슈: [#59 알림 도메인 — Notification 엔티티 + 공통 발송 모듈](https://github.com/GBSW-ReMake/GONE-server-V1/issues/59)
전체 도메인 마스터 기획서: [1_notification-domain.md](./1_notification-domain.md) — 이
문서는 마스터 기획서의 "1단계(인앱 알림함)" 중 **다른 도메인이 알림을 "보내는" 데
필요한 최소 단위(엔티티 + 공통 발송 모듈)만** 좁힌 것이다. 목록 조회/읽음 처리 REST
API는 이번 이슈 범위 밖 — 아래 "범위" 참고.

## 범위
마스터 기획서가 "1단계"로 묶었던 범위(엔티티 저장 + 목록 조회 + 읽음 처리 API) 중
이번 이슈는 **엔티티 + `NotificationService.send(...)`까지만** 다룬다. 다른 도메인
(`outing`/`schoolcamp`)이 알림을 "보내는" 데 필요한 전부는 이 두 가지뿐이다 — 알림을
"조회"하는 화면(목록/읽음 처리 API)은 아직 없어도 되고, 그 화면은 엔티티 스키마에
영향을 주지 않는 순수 추가 계층이라 나중에 붙여도 이번 이슈의 결과물을 전혀 고칠
필요가 없다(마스터 기획서가 2/3단계에 적용한 "시그니처/스키마는 먼저 고정, 그 위에
얹는 계층은 나중에" 원칙과 같은 결).

`is_read`/인덱스 등 엔티티 필드는 후속 조회 API가 그대로 재사용할 수 있도록 처음부터
최종 스키마로 설계한다(아래 "데이터 모델" 참고) — 엔티티만 지금 완결시키고, 나중에
조회 API가 붙을 때 마이그레이션을 다시 만들 필요가 없게 한다.

이번 이슈에 포함되지 않는 것(전부 후속 이슈, 미생성):
- 목록 조회 / 읽음 처리(단건·모두) / 안 읽은 개수 4개 엔드포인트
- FCM 디바이스 토큰 + 푸시 발송(마스터 기획서 2단계)
- `outing`/`schoolcamp`가 `send(...)` 실제 호출(마스터 기획서 3단계)

## 엔드포인트
없음. 이 이슈는 컨트롤러를 추가하지 않는다(`SecurityConfig` 변경도 없음).

## 공통 발송 모듈 — `NotificationService`
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
  호출자(향후 `outing`/`schoolcamp`) 트랜잭션과 함께 롤백되는 게 오히려 맞는 동작이다
  (마스터 기획서 "정책 가정" 참고).
- `type`은 자유 문자열(예: `"OUTING_APPROVED"`) — `notification` 패키지가 다른 도메인의
  enum을 참조하지 않기 위해 의도적으로 비타입 문자열로 둔다.
- **이번 이슈에는 이 메서드를 실제로 호출하는 곳이 없다** — 단위 테스트로만 동작을
  검증한다. `outing`/`schoolcamp` 연동, 조회/읽음 API는 각각 후속 이슈에서 진행한다.

## 데이터 모델 변경
### `Notification` 엔티티 (`notification` 테이블, 신규)
- `id` — 내부 PK
- `user_id` — 수신자(`User` FK, `@ManyToOne(fetch = LAZY)`, `outing`의 `student`/`teacher`
  참조 방식과 동일)
- `title` — 알림 제목(`VARCHAR(100)`)
- `body` — 알림 본문(`VARCHAR(500)`)
- `type` — 발송 도메인이 붙이는 분류 태그(`VARCHAR(50)`, nullable)
- `is_read` — 읽음 여부(`BOOLEAN NOT NULL DEFAULT false`) — 후속 조회 API가 쓸 컬럼이지만,
  엔티티 완결성을 위해 지금 만든다(위 "범위" 참고)
- `created_at` — 발송(저장) 시각(`@CreationTimestamp`)

인덱스: `(user_id, created_at)` — 후속 조회 API의 목록 조회가 이 조합으로 정렬/필터할
것이므로 지금 같이 만들어둔다.

### Flyway 마이그레이션
`V9__add_notification.sql`(신규) — `notification` 테이블 생성(위 컬럼 + 인덱스 전부
포함, 후속 조회 API 착수 시 추가 마이그레이션 불필요).

## 영향 받는 기존 코드
- 신규 패키지 `notification`(`entity`/`repository`/`service`) — 이 프로젝트에 새 도메인
  패키지가 추가되는 사례. `controller`/`dto`/`exception` 하위 패키지는 후속 조회 API
  이슈에서 추가.
- 신규 테스트: `NotificationServiceTest`(정상 저장, `type` null 허용).

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 저장만 책임진다. 조회/읽음 처리, FCM 발송, 실제 도메인 연동은
   전부 후속 이슈로 분리했다.
2. **빠르게 시작하기**: 엔드포인트가 없어 해당 없음.
3. **일관성**: 엔티티 필드/네이밍은 `outing`(FK를 `@ManyToOne`으로, 시각은
   `@CreationTimestamp`)과 동일한 패턴을 재사용.
4. **의미 있는 오류**: 엔드포인트가 없어 해당 없음(저장 실패는 그대로 전파).
5. **확장성/성능**: `(user_id, created_at)` 인덱스를 후속 조회 API 착수 전에 미리
   마련해 둔다.
6. **하위 호환성**: `send(...)` 시그니처는 이번 이슈에서 확정하고 FCM 단계까지 그대로
   유지할 계획(마스터 기획서 참고) — 호출부가 이후 단계와 무관하게 안정적으로 동작한다.

## 리스크 및 고려사항
- **아무도 호출하지 않는 서비스를 먼저 만드는 것에 대한 우려**: `send(...)`의 실제
  호출부가 이번 이슈에 없어, 이 자체만으로는 사용자가 체감할 기능이 없다(단위 테스트로만
  검증). 목적은 (1) 다음 이슈(외출증 알림 연동 등)에서 바로 재사용 가능한 상태로
  만들어두는 것, (2) 조회 API가 필요할 때 엔티티/마이그레이션을 다시 건드리지 않아도
  되게 미리 완결시켜두는 것이다.
- **조회 API를 별도 이슈로 미루는 것에 대한 트레이드오프**: 이번 이슈만으로는 사용자가
  "알림을 봤다"고 체감할 수 있는 기능이 전혀 없다(저장만 있고 조회가 없음). 다만 조회
  API는 이 엔티티 위에 얹는 순수 추가 계층이라 나중에 붙여도 이번 이슈의 산출물(엔티티/
  서비스/마이그레이션)을 전혀 재작업하지 않는다.
- **알림 목록 보존 기간 미정** — 마스터 기획서 "아직 결정 안 된 것"에 남김, 이번
  이슈에서 삭제/보존 정책을 구현하지 않는다(무기한 저장).

## 테스트 방법
컨트롤러가 없어 Postman 검증 대상이 없다.
1. 단위 테스트(`NotificationServiceTest`)로 `send(...)` 저장 동작 검증(정상 저장, `type`
   null 허용, `user_id`/`title`/`body`/`is_read` 기본값 확인)
2. `./gradlew build`, `./gradlew test`, `./gradlew checkstyleMain` 로컬 통과 확인 + CI
   통과 확인
