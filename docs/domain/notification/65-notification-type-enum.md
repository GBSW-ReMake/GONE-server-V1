# #65 Notification.type을 Enum으로 전환 — 기획서

관련 이슈: [#65 Notification.type을 Enum으로 전환 (도메인별 이모지 매핑용)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/65)
관련 선행 이슈: [#59 알림 도메인 — Notification 엔티티 + 공통 발송 모듈](../notification/59-notification-core.md)
전체 도메인 마스터 기획서: [1_notification-domain.md](./1_notification-domain.md)

## 개요/목적
프론트엔드가 알림 목록에서 `type` 값에 따라 이모지를 매핑해 보여주려면, `type`이 어떤
값들로 올 수 있는지 코드로 보장돼야 한다. 지금 `Notification.type`(#59)은 자유
문자열(`VARCHAR(50)`, nullable)이라 이 보장이 없다 — 오타/새 값 추가가 컴파일 타임에
걸러지지 않는다. `type`을 정해진 값만 허용하는 Enum(`NotificationType`)으로 바꾼다.

**#59 마스터 기획서([1_notification-domain.md](./1_notification-domain.md) "도메인 모델")는
`type`을 자유 문자열로 둔 이유로 "`notification` 패키지가 `outing`/`schoolcamp` 같은
구체 도메인의 enum을 참조하면 역방향 의존이 생긴다"를 들었다. 이번 이슈는 그 우려를
Enum을 `notification` 패키지 안에서 직접 정의하는 방식으로 해소한다** — `outing`/
`schoolcamp`/`conduct`는 이미 `NotificationService`를 호출하기 위해 `notification`
패키지에 의존하므로(역방향이 아니라 원래 방향), 그 패키지가 소유한 `NotificationType`을
함께 참조하는 것은 새로운 의존을 추가하지 않는다. 즉 우려했던 "notification이 outing을
아는 상황"은 여전히 발생하지 않는다 — 반대로 "outing이 notification의 타입을 아는
상황"일 뿐이고, 이건 애초에 `send(...)`를 호출하기 위해 이미 성립해 있던 관계다.

## 작업 범위
- `com.remake.gone.notification.enums.NotificationType` 신규 — 값 목록은 아래 "Enum
  값 목록" 참고
- `Notification.type`을 `String`에서 `NotificationType`으로 변경(`@Enumerated(EnumType.STRING)`,
  `outing.entity.Outing`의 `status` 필드와 동일한 패턴)
- `NotificationService.send(Long userId, String title, String body, String type)`의
  네 번째 파라미터를 `NotificationType type`으로 변경
- `NotificationServiceTest` 갱신(문자열 리터럴 → Enum 상수)
- Flyway 마이그레이션 불필요 — 컬럼은 이미 `VARCHAR(50)`이고 Enum도 `EnumType.STRING`으로
  같은 문자열 컬럼에 저장되므로 DB 스키마 변경이 없다(아래 "데이터 모델 변경" 참고)

## 엔드포인트
없음. 이 이슈는 컨트롤러를 추가/변경하지 않는다 — `NotificationService.send(...)`
시그니처만 바뀐다. 실제 호출부(`outing`/`schoolcamp`/`conduct` 연동)는 각 도메인의
후속 이슈에서 이 Enum 상수를 그대로 가져다 쓴다.

## Enum 값 목록
프론트엔드가 이 값 하나당 이모지 1개를 매핑한다(보스 확정) — 이벤트 단위(승인/거절/
리마인더 등)가 아니라 **도메인 단위**로 값을 나눈다. `conduct`는 상점과 벌점을 같은
도메인 안에서도 서로 다른 이모지로 구분해야 해서 둘로 나눈다.

```java
public enum NotificationType {
  OUTING,     // 외출증(outing) 도메인의 모든 알림(승인/거절/복귀 리마인더 등)
  SCHOOLCAMP, // 스쿨캠핑(schoolcamp) 도메인의 모든 알림(팀원 초대/외출 리마인더 등)
  MERIT,      // 상점(conduct 도메인, 상점 부여/취소)
  DEMERIT,    // 벌점(conduct 도메인, 벌점 부여/취소)
}
```
- 각 값은 도메인(또는 `conduct`의 상/벌점처럼 이모지가 갈리는 하위 구분) 전체를 대표한다
  — 같은 도메인 안에서 세부 이벤트가 늘어나도(예: `outing`에 새 알림 종류가 추가돼도)
  이 Enum에 값을 추가하지 않는다. `title`/`body`가 이벤트별 세부 문구를 담당하고,
  `type`은 오직 "이모지를 뭘로 보여줄지"만 결정한다.
- 실제 호출부가 아직 없는 도메인(`schoolcamp`/`conduct`)도 마스터 기획서에 알림 트리거
  지점이 이미 명시돼 있어(각 마스터 기획서 "알림 트리거"/"외출 도메인 연동" 절) 값 자체는
  지금 정의해도 안전하다 — `Notification` 엔티티가 #59에서 미리 완결된 스키마로 설계된
  것과 같은 이유(나중에 호출부가 붙을 때 이 Enum을 다시 건드릴 필요가 없게).

## 공통 발송 모듈 — `NotificationService` (변경)
```java
public void send(Long userId, String title, String body, NotificationType type) {
  Notification notification = Notification.builder()
      .user(user)
      .title(title)
      .body(body)
      .type(type)
      .isRead(false)
      .build();
  notificationRepository.save(notification);
}
```
시그니처의 파라미터 개수/순서는 그대로 유지하고 네 번째 파라미터의 타입만 바뀐다 — 이미
호출부가 없는 상태(#59 완료 시점 기준)라 하위 호환성을 깨는 대상 자체가 없다.

## 데이터 모델 변경
### `Notification` 엔티티 — `type` 필드
- 변경 전: `@Column(length = 50) private String type;`
- 변경 후: `@Enumerated(EnumType.STRING) @Column(length = 50) private NotificationType type;`
- `nullable`은 그대로 지정하지 않는다(#59와 동일하게 nullable 허용) — 알림 목록 조회
  화면에서 이모지 매핑이 안 되는 시스템 공지성 알림 등, 특정 도메인 이벤트로 분류되지
  않는 알림을 위한 여지를 남긴다.
- 컬럼 길이(`50`)는 그대로 유지 — 가장 긴 값(`SCHOOLCAMP`, 10자)도 여유 있게 들어간다.

### Flyway 마이그레이션
불필요. `@Enumerated(EnumType.STRING)`은 Enum 상수 이름을 그대로 문자열로 저장하므로,
DB 입장에서는 이전과 동일한 `VARCHAR(50)` 컬럼에 문자열이 들어가는 것과 차이가 없다.
`ALTER TABLE`이 필요한 타입 변경(예: `INT`로 바꾸는 경우)이 아니다.

## 영향 받는 기존 코드
- `com.remake.gone.notification.enums.NotificationType`(신규 패키지 `notification.enums`
  — `outing.enums`/`meal.enums`/`gbsw.enums`와 동일한 하위 패키지 구조)
- `Notification.java`: `type` 필드 타입 변경
- `NotificationService.java`: `send(...)` 네 번째 파라미터 타입 변경
- `NotificationServiceTest.java`: 문자열 리터럴(`"OUTING_APPROVED"`, `null`)을
  `NotificationType.OUTING`, `null`(Enum도 그대로 null 허용)로 변경

## API 설계 6원칙 체크
엔드포인트 변경이 없어 해당 없음.

## 리스크 및 고려사항
- **#59 마스터 기획서의 명시적 설계를 뒤집는 변경이다.** 위 "개요/목적"에서 다뤘듯,
  실제로는 우려했던 역방향 의존이 발생하지 않아 안전하다고 판단하지만, 이 판단이
  틀렸다면(예: 나중에 `notification` 패키지가 각 도메인 enum까지 참조해야 하는 상황이
  생기면) 그건 다시 별도로 검토할 문제다.
- **`schoolcamp`/`conduct`는 아직 구현되지 않은 도메인이다.** 다만 값이 도메인 단위라
  세부 이벤트 설계가 바뀌어도(예: schoolcamp에 리마인더 종류가 늘어나도) 이 Enum
  자체는 영향받지 않는다 — 새 도메인이 통째로 추가되는 경우에만 값을 늘리면 된다.
- **Enum 값 이름이 Notion API 명세서에 그대로 노출된다.** 프론트가 이 문자열 그대로
  이모지 맵의 키로 쓸 것이므로, 한 번 정하면 이름을 바꿀 때 프론트 코드도 함께
  고쳐야 한다 — 이번 이슈에서 신중하게 확정한다.

## 완료 조건 (Definition of Done)
- `Notification.type`이 `NotificationType` Enum
- `NotificationServiceTest` 통과(Enum 값으로 갱신)
- 로컬 빌드/테스트/체크스타일 통과, CI 통과
- Notion 문서에 전체 Enum 값 목록 반영(프론트가 이모지 매핑표를 만들 수 있도록)

## 테스트 방법
컨트롤러가 없어 Postman 검증 대상이 없다.
1. 단위 테스트(`NotificationServiceTest`)로 `send(...)`가 `NotificationType` 값을 그대로
   저장하는지 확인(기존 "정상 저장"/"type null 허용" 케이스를 Enum 기준으로 유지)
2. `./gradlew build`, `./gradlew test`, `./gradlew checkstyleMain` 로컬 통과 확인 + CI
   통과 확인
