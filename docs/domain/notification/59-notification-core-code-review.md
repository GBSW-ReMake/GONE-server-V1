# #59 알림 도메인 — Notification 엔티티 + 공통 발송 모듈 — 코드 리뷰 결과

관련 기획서: [59-notification-core.md](./59-notification-core.md)
형식 규칙: [code-review-template.md](../../rules/code-review-template.md)

## 리뷰 범위/방법

- 대상: `git diff dev...feat/#59-notification-core`(5개 파일, 187줄 추가 — 신규
  파일만 있고 기존 파일 수정은 없음).
- 변경 파일: `Notification.java`(엔티티, 신규), `NotificationRepository.java`(신규),
  `NotificationService.java`(신규), `V9__add_notification.sql`(신규 마이그레이션),
  `NotificationServiceTest.java`(신규 테스트).
- 기획서(`59-notification-core.md`) 대비 범위 초과 변경 없음을 확인했다. 컨트롤러/DTO/
  예외 패키지 없음, `SecurityConfig` 변경 없음, `send(...)`를 실제로 호출하는 곳 없음 —
  전부 기획서 "범위"/"엔드포인트: 없음" 절과 일치.
- 코드 스타일(`code-style.md`): `./gradlew checkstyleMain checkstyleTest`를 직접
  실행해 통과를 확인했다(`google_checks.xml`, `maxWarnings = 0` 기준 위반 없음).
- 테스트: `NotificationServiceTest`가 이미 빌드에 반영돼 있어 결과 리포트
  (`build/test-results/test/TEST-...NotificationServiceTest$Send.xml`)로 2건 모두
  통과(실패 0, 에러 0)한 것을 확인했다.
- 엔티티/서비스 컨벤션은 `outing` 도메인(`Outing.java`, `OutingService.java`)과
  `user` 도메인(`User.java`)을 기준으로 대조했다 — Lombok 조합
  (`@NoArgsConstructor(PROTECTED)` + `@AllArgsConstructor` + `@Builder`), FK
  매핑(`@ManyToOne(fetch = LAZY)` + `@JoinColumn`), `@CreationTimestamp` 사용,
  마이그레이션의 `KEY idx_...` 네이밍은 전부 기존 패턴과 일치했다.
- 문장/주석 규칙(`sentence-refinement.md` 원칙 6)도 확인했다 — 클래스/필드 Javadoc이
  "무엇을"이 아니라 "왜"(예: `type`을 자유 문자열로 둔 이유, 저장 실패를 전파하는 이유)를
  설명하고 있어 위반 없음.
- 독립 에이전트(컨텍스트 격리, 같은 diff + 기획서만 전달)로 `code-review` 스킬 리뷰를 한
  라운드 더 돌려 교차 검증했다. 아래 1번 항목(FK를 리포지토리 조회 없이 직접 구성)은 두
  리뷰에서 동일하게 도출됐고, 교차 검증 라운드가 `GlobalExceptionHandler`까지 추적해
  구체적인 실패 응답(409 오분류)을 짚어줘서 1번 항목에 반영했다. 2번 항목(길이 검증 누락)도
  교차 검증에서 새로 나와 추가했다.

---

## 1. 🟡 Medium — FK 참조를 리포지토리 조회 없이 `User.builder().id(userId).build()`로 직접 생성함

**문제**: `NotificationService.send`(`src/main/java/com/remake/gone/notification/service/NotificationService.java:33`)는
아래처럼 `id`만 채운 `User` 인스턴스를 즉석에서 만들어 연관관계에 꽂는다.

```java
Notification notification = Notification.builder()
    .user(User.builder().id(userId).build())
    ...
```

이 프로젝트의 기존 컨벤션은 FK로 참조할 엔티티를 항상 리포지토리로 조회해서 쓴다 —
`OutingService`는 `userRepository.findByIdForUpdate(studentUserId).orElseThrow(...)`
(`OutingService.java:93`)와 `userRepository.findById(teacherUserId).orElseThrow(...)`
(`OutingService.java:380`)로, `TimetableService`도 `userRepository.findById(userId)
.orElseThrow(...)`(`TimetableService.java:66`)로 참조 대상을 항상 조회한다. 반면
`NotificationService`는 `UserRepository`를 아예 주입받지 않고, Lombok
`@AllArgsConstructor` + `@Builder`로 만든 "가짜" `User`(`id`만 있고 `gbsw`/`loginId`/
`passwordHash`/`name`/`phoneNumber`는 전부 `null`)를 그대로 연관관계 값으로 쓴다.

지금 당장은 `Notification.user`에 cascade가 없어(`Notification.java`의 `@ManyToOne`에
`cascade` 속성 없음) Hibernate가 이 `user`의 `id`만 읽어 FK 컬럼 값으로 쓰고 연관 엔티티
자체를 INSERT/UPDATE하지 않으므로 동작은 한다. 다만 이 방식은 두 가지 잠재 위험을
갖는다.

1. **존재하지 않는 `userId`가 들어오면 "리소스 중복"이라는 잘못된 409로 응답된다.**
   `findById` 방식이면 `orElseThrow`로 명확한 시점에 실패하지만, 지금 구현은 `save()`
   시점에 `notification` 테이블의 FK 제약조건 위반(`DataIntegrityViolationException`)으로만
   드러난다. 기획서의 "저장 실패는 그대로 예외 전파한다"는 정책 자체와는 어긋나지
   않지만(예외가 삼켜지지는 않음), 이 프로젝트는 이미
   `GlobalExceptionHandler.handleDataIntegrityViolation`
   (`src/main/java/com/remake/gone/common/exception/GlobalExceptionHandler.java:153-162`)에서
   **모든** `DataIntegrityViolationException`을 `CommonErrorCode.CONFLICT`(`409`, "이미
   존재하는 리소스입니다")로 매핑하는 전역 안전망을 두고 있다. 이 핸들러의 Javadoc이
   명시하듯 원래는 `existsByXxx()` → `save()` 사이의 unique 제약 레이스만 잡으려는
   의도인데, `DataIntegrityViolationException`은 FK 위반·NOT NULL 위반·길이 초과 등
   Spring이 감싸는 데이터 무결성 예외 전반의 부모 클래스라 이 경우도 그대로 걸린다.
   결과적으로 향후 `outing`/`schoolcamp`가 삭제된 사용자나 오타난 `userId`로
   `send()`를 호출하면, 클라이언트는 "존재하지 않는 사용자"가 아니라 "이미 존재하는
   리소스"라는 사실과 반대되는 오류 메시지를 받는다. 이번 이슈에는 실제 호출부가 없어
   당장 재현되지는 않지만, 바로 다음 이슈(외출증 알림 연동)에서 그대로 노출될 설계
   결함이다.
2. **이후 이 연관관계에 cascade가 추가되면 조용히 깨진다.** 누군가 나중에
   `@ManyToOne(cascade = CascadeType.PERSIST)`나 `CascadeType.ALL`을 추가하는 순간(다른
   요구사항으로 충분히 있을 수 있는 변경), `id`만 있고 나머지 필드가 전부 `null`인 이
   `User` 인스턴스가 그대로 INSERT/MERGE 대상이 되어 `user` 테이블의 `NOT NULL` 제약
   (`login_id`, `password_hash`, `name`, `phone_number`)을 위반하거나, 최악의 경우 기존
   사용자 행을 `null`로 덮어쓸 수 있다. 지금 리뷰 시점에는 재현되지 않지만, 코드만
   봐서는 "이 `User`는 FK 값 추출 전용이고 실제로 로드된 엔티티가 아니다"라는 의도가
   드러나지 않아 향후 수정자가 이 전제를 모르고 cascade를 추가할 위험이 있다.

**해결 방안**:
1. **`UserRepository`를 주입받아 `userRepository.getReferenceById(userId)` 사용** —
   Spring Data JPA가 제공하는 프록시 참조라 추가 `SELECT` 없이 지금과 동일한 성능을
   유지하면서도, "이 값은 실제 로드된 엔티티가 아니라 FK 참조 전용 프록시"라는 의도가
   코드 자체로 드러난다. 존재하지 않는 `userId`에 대한 실패 시점(프록시 필드 접근 시
   `EntityNotFoundException`)은 지금과 크게 다르지 않지만, 최소한 Lombok으로 손수 만든
   "반쪽짜리 엔티티"라는 landmine은 제거된다. 트레이드오프: `notification` 패키지가
   `user` 도메인의 `UserRepository`에 의존을 하나 더 추가한다(이미 `User` 엔티티 자체는
   import하고 있으므로 추가 결합은 크지 않음).
2. **`userRepository.findById(userId).orElseThrow(() -> new CustomException(...))`로
   `OutingService`/`TimetableService`와 완전히 동일한 패턴 사용** — 기존 컨벤션과
   가장 일관되고, 존재하지 않는 `userId`를 저수준 DB 예외가 아니라 명확한
   `CustomException`으로 조기에 실패시킬 수 있다. 트레이드오프: `send()` 호출마다
   `SELECT`가 하나 추가된다(다만 이 메서드는 알림 저장이라는 부가 작업이라 호출 빈도가
   외출증 승인/거절 같은 트랜잭션 대비 크지 않을 것으로 예상되어 비용 대비 안전성 이득이
   더 클 수 있음). `notification` 패키지가 별도의 `ErrorCode`를 새로 정의할지, 아니면
   `CommonErrorCode`를 재사용할지도 함께 정해야 한다.
3. **지금 상태를 유지하되 의도를 주석으로 명시** — 성능(추가 쿼리 없음)을 의도적으로
   택한 것이라면, `NotificationService.send()`에 "이 `User`는 FK 컬럼 값 추출 전용이며
   cascade를 추가하면 안 된다"는 주석을 남겨 향후 수정자가 실수로 cascade를 붙이는 것을
   막는다. 다만 이는 "존재하지 않는 `userId`가 저수준 예외로만 드러난다"는 1번 위험은
   그대로 남긴다.

---

## 2. 🟡 Medium — `title`/`body` 길이를 저장 전에 검증하지 않아 정상 입력도 같은 오분류된 409로 실패할 수 있음

**문제**: `NotificationService.send`(`NotificationService.java:34`)는 호출자가 넘긴
`title`/`body` 문자열 길이를 전혀 검증하지 않고 그대로 `Notification.builder()`에
넣는다. `Notification` 엔티티는 `@Column(nullable = false, length = 100)`(`title`),
`@Column(nullable = false, length = 500)`(`body`)로 매핑돼 있고,
`V9__add_notification.sql`도 동일하게 `VARCHAR(100)`/`VARCHAR(500)`로 컬럼을 만든다.
이 프로젝트의 MySQL은 `deploy/docker-compose.dev.yml:3`에서 `mysql:8.0` 이미지를
쓰고 `application.yml`(`src/main/resources/application.yml:6`)의 JDBC URL에도
`sql_mode`를 완화하는 설정이 없다 — 즉 MySQL 8.0 기본값인 `STRICT_TRANS_TABLES`가
그대로 적용되어, 100자/500자를 초과하는 값은 자동 절단(truncate)되지 않고 데이터
초과 오류로 INSERT 자체가 실패한다. 이 오류 역시 Spring이
`DataIntegrityViolationException`으로 감싸므로, 위 1번 항목과 똑같이
`GlobalExceptionHandler.handleDataIntegrityViolation`을 거쳐 "이미 존재하는
리소스입니다"(`409`)라는, 실제 원인(제목/본문이 너무 김)과 무관한 오류로 응답된다.

호출부가 아직 없어 이번 이슈에서 재현되지는 않지만, 후속 이슈에서 `outing`/
`schoolcamp`가 동적으로 조합한 문자열(예: 외출증 사유 + 학생 이름 + 시각을 이어 붙인
제목)을 그대로 `title`에 넘기면, 드물지 않게 100자를 넘길 수 있는 흔한 실수 경로다.

**해결 방안**:
1. **`send()` 진입부에 명시적 길이 검증 추가** — `title.length() > 100` /
   `body.length() > 500`이면 `CustomException` + 전용 `ErrorCode`(예:
   `NOTIFICATION_TITLE_TOO_LONG`)를 던진다. 실패 원인이 응답에 정확히 드러나고, DB
   제약과 애플리케이션 검증이 이중 방어선을 이룬다. 트레이드오프: `notification`
   패키지에 `exception` 서브패키지(이번 이슈 범위 밖으로 명시됐던 부분)를 새로
   만들어야 한다 — 기획서 범위를 벗어나므로 별도 후속 이슈로 처리할지, 이번 PR에
   포함할지 먼저 정해야 한다.
2. **`GlobalExceptionHandler`가 아니라 호출자 책임으로 명시** — 검증 코드를 추가하지
   않는 대신, `NotificationService.send()`의 Javadoc에 "`title`/`body`는 각각 100자/
   500자를 넘으면 안 되며, 초과 시 호출자가 사전에 자르거나 검증해야 한다"는 제약을
   명시해 계약을 문서화한다. 구현 비용은 가장 낮지만, 실수로 초과 값을 넘기는 호출부가
   생기면 여전히 원인을 알기 어려운 409로만 드러난다는 근본 문제는 남는다.
3. **1번 항목의 해결책과 함께 처리** — `DataIntegrityViolationException`을 다루는
   근본 원인(FK 위반과 길이 초과 둘 다)이 같으므로, 1번 항목에서 리포지토리 기반 조회로
   전환하면서 동시에 길이 검증도 같은 자리에 추가하는 편이 `notification` 패키지에
   예외 처리 계층을 한 번만 새로 만들면 되어 효율적이다.

---

## 3. 🟢 Low — "저장 실패는 예외를 그대로 전파한다"는 기획서 명시 동작이 테스트로 고정되지 않음

**문제**: 기획서(`59-notification-core.md:50-52`)는 "저장 실패는 그대로 예외 전파한다
(삼키지 않음) — 알림 저장은 이 모듈의 핵심 책임이라, 호출자 트랜잭션과 함께 롤백되는
게 오히려 맞는 동작"이라고 이 서비스의 핵심 계약 중 하나로 명시한다. 하지만
`NotificationServiceTest`(`src/test/java/.../notification/service/NotificationServiceTest.java`)의
두 테스트(`savesNotificationWithGivenValues`, `allowsNullType`)는 모두 정상 저장
경로만 검증하고, `notificationRepository.save(...)`가 예외를 던졌을 때
`NotificationService.send()`가 그 예외를 삼키지 않고 그대로 전파하는지는 아무 테스트도
확인하지 않는다. `NotificationService.send()`에 try-catch가 전혀 없어 지금은 당연히
전파되지만, 향후 로깅/재시도 로직 등을 추가하다 실수로 예외를 삼키는 회귀가 생겨도
테스트가 잡아주지 못한다.

기획서의 "테스트 방법" 절이 명시적으로 요구하는 케이스(정상 저장, `type` null 허용,
기본값 확인)는 이미 전부 충족하므로 이 자체가 기획서 위반은 아니다.

**해결 방안**:
1. **예외 전파를 확인하는 테스트 1개 추가** — `given(notificationRepository.save(any()))
   .willThrow(new RuntimeException(...))` 후 `assertThatThrownBy(() ->
   notificationService.send(...))`로 동일 예외가 그대로 전파되는지 확인한다. 비용이
   테스트 메서드 하나 수준으로 낮고, 기획서가 명시한 계약을 코드로 고정한다는 이점이
   가장 크다.
2. **지금 상태 유지** — `send()` 구현이 단 두 줄(빌더 + `save()`)이라 try-catch가 없다는
   사실만으로 예외가 전파됨을 코드 리뷰만으로도 바로 확인할 수 있어, 이 정도로 단순한
   메서드에 "예외를 안 삼킨다"는 사실만을 위한 테스트를 추가하는 것은 과잉일 수 있다.
   다만 이 경우 향후 로직이 복잡해질 때(로깅 추가 등) 이 계약이 깨지기 쉬워진다는 점은
   감수해야 한다.

---

## 요약

Critical/High 없음. Medium 2건 — (1) `User.builder().id(userId).build()`로 FK를 직접
구성해 리포지토리 조회 컨벤션과 어긋나고, 존재하지 않는 `userId`가 `GlobalExceptionHandler`를
거쳐 실제 원인과 반대되는 "이미 존재하는 리소스" 409로 응답됨. (2) `title`/`body` 길이를
저장 전에 검증하지 않아, 100자/500자를 넘는 정상적인 입력도 같은 방식으로 오분류된 409가
됨. 두 항목 모두 근본 원인(`DataIntegrityViolationException`을 전역 핸들러가 "리소스
중복"으로만 해석)이 같고, 이번 이슈에는 실제 호출부가 없어 지금 당장 재현되지는 않지만
바로 다음 이슈(`outing`/`schoolcamp` 연동)에서 노출될 설계 결함이다. Low 1건(기획서가
명시한 "예외 전파" 계약이 테스트로 고정되지 않음).

범위·컨벤션·스타일·테스트 통과 여부는 모두 확인 완료. 엔티티 필드/인덱스 설계, 마이그레이션
컬럼 순서, Javadoc 품질은 기획서 및 기존 도메인 패턴과 정확히 일치해 별도 지적 사항 없음.

## 반영 결과

- **Medium 1**: 해결 방안 1번(`userRepository.getReferenceById(userId)`)을 적용했다.
  `UserRepository` 의존을 추가하고, 손수 만든 "반쪽짜리 `User`" 대신 프록시 참조를 쓰도록
  변경. 테스트도 `UserRepository`를 목(mock)으로 추가해 `getReferenceById` 스텁을 반영.
- **Medium 2**: 해결 방안 2번(호출자 책임 명시)을 적용했다. `exception` 서브패키지 추가는
  기획서가 명시한 범위 밖이라, 별도 검증 로직 대신 `send()` Javadoc에 100자/500자 제약을
  명시하는 선에서 마무리. 실제 길이 검증은 `outing`/`schoolcamp` 연동 이슈에서 호출자 쪽에
  필요성이 다시 확인되면 그때 추가한다.
- **Low 1**: 이번 라운드에서는 보류(과잉 방지). 후속 이슈에서 로직이 복잡해지면 재검토.
- 위 변경 후 `./gradlew checkstyleMain checkstyleTest test`(notification 패키지)와
  `./gradlew build` 전체 통과 확인.
