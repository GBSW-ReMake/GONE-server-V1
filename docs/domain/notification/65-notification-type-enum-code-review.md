# #65 Notification.type을 Enum으로 전환 — 코드 리뷰 결과

관련 기획서: [65-notification-type-enum.md](./65-notification-type-enum.md)
선행 이슈 코드 리뷰: [59-notification-core-code-review.md](./59-notification-core-code-review.md)
형식 규칙: [code-review-template.md](../../rules/code-review-template.md)

## 리뷰 범위/방법

- 대상: `git diff dev feat/#65-notification-type-enum`(`src/` 기준 4개 파일, 3커밋
  — 기획서 커밋 1개 + feat 커밋 2개 + test 커밋 1개).
- 변경 파일: `NotificationType.java`(신규, `notification.enums` 패키지),
  `Notification.java`(`type` 필드 `String` → `NotificationType`),
  `NotificationService.java`(`send(...)` 네 번째 파라미터 타입 변경),
  `NotificationServiceTest.java`(문자열 리터럴 → Enum 상수).
- 기획서(`65-notification-type-enum.md`) 대비 범위 초과 변경이 없는지 `git grep`으로
  `NotificationType`/`NotificationService` 사용처를 브랜치 전체에서 검색해 확인했다 —
  테스트 파일을 제외하면 실제 호출부가 여전히 없고(기획서 "엔드포인트: 없음" 절과 일치),
  컨트롤러/DTO 변경 없음, `schoolcamp`/`conduct` 패키지 모두 아직 존재하지 않음(기획서
  "리스크 및 고려사항" 절이 명시한 전제와 일치), 신규 Flyway 마이그레이션 파일도 없음(기획서
  "Flyway 마이그레이션: 불필요" 절과 일치)을 확인했다.
- `@Enumerated(EnumType.STRING)` + `@Column(length = 50)` 조합과 어노테이션 순서
  (`@Enumerated`를 `@Column` 위에 배치)를 `outing.entity.Outing.status`(동일 패턴,
  `length = 20`)와 대조해 일치를 확인했다. 컬럼 길이(50)는 가장 긴 값(`SCHOOLCAMP`, 10자)
  대비 충분한 여유가 있고, 기획서가 명시한 대로 마이그레이션 없이 기존 `VARCHAR(50)` 컬럼에
  그대로 들어간다.
- 신규 패키지 `notification.enums`의 위치를 `outing.enums`/`meal.enums`/`gbsw.enums`와
  대조해 기존 컨벤션과 일치함을 확인했다 — `code-style.md`가 요구하는 "목록에 없는 폴더는
  근거 필요" 조항에 해당하지 않는다(이미 쓰이고 있는 하위 패키지 패턴).
- Import 정렬(ASCII 알파벳 순, 와일드카드 없음), Javadoc 배치(공개 API/필드에만), 한 줄
  100자 제한을 4개 파일 전부 대조했다 — 위반 없음.
- 문장/주석 규칙(`sentence-refinement.md` 원칙 6)도 확인했다 — 신규/수정된 Javadoc
  (`NotificationType` 클래스, `Notification.type` 필드, `NotificationService.send`의
  `type` 파라미터)이 전부 "무엇"이 아니라 "왜"(프론트엔드 이모지 매핑이 목적이라는 점,
  이벤트 단위가 아니라 도메인 단위로 값을 나눈 이유)를 설명하고 있어 위반 없음.
- `59-notification-core-code-review.md`의 "반영 결과"(`userRepository.getReferenceById`
  패턴, `title`/`body` 100자/500자 제약을 Javadoc으로 명시하는 계약)가 이번 diff에서 그대로
  유지되는지 확인했다 — `NotificationService.java`의 diff가 해당 두 지점(`getReferenceById`
  호출부, 길이 제약 Javadoc 문구)을 전혀 건드리지 않아 회귀 없음.
- 독립 에이전트(컨텍스트 격리, 같은 diff + 기획서만 전달)로 `code-review` 스킬 리뷰를 한
  라운드 더 돌려 교차 검증했다 — correctness/reuse/simplification/efficiency 관점에서
  findings 0건. 시그니처 변경(`String` → `NotificationType`)이 파급시킬 다운스트림 호출부가
  없다는 점, DB 컬럼 길이 여유, 테스트가 `==` 비교로 Enum을 올바르게 검증한다는 점을 근거로
  들었다.

---

## 1. 🟢 Low — 마스터 기획서(`1_notification-domain.md`)가 이번 이슈로 뒤집힌 설계 근거를 그대로 남겨두고 있음

**문제**: `docs/domain/notification/1_notification-domain.md`("도메인 모델" 절,
99~103번째 줄)는 `type`을 "발송한 도메인이 자유롭게 붙이는 분류 태그"로 설명하고 예시로
`"OUTING_APPROVED"`(이벤트 단위 문자열)를 든다. 같은 절은 "`outing`/`schoolcamp` 같은
구체 도메인의 enum을 `notification` 패키지가 참조하면 역방향 의존이 생기므로 일부러 자유
문자열로 둔다"는 근거를 명시하고, 엔드포인트 응답 예시(127~150번째 줄)도
`"type": "OUTING_APPROVED"`를 그대로 보여준다.

이번 이슈(#65)의 기획서(`65-notification-type-enum.md` "개요/목적")는 정확히 이 근거
("역방향 의존")를 반박하고 `type`을 Enum으로 전환했고, 실제 Enum 값도 이벤트 단위
(`OUTING_APPROVED`)가 아니라 도메인 단위(`OUTING`/`SCHOOLCAMP`/`MERIT`/`DEMERIT`)로
확정했다. 하지만 이번 diff는 `1_notification-domain.md`를 전혀 수정하지 않는다. 마스터
기획서만 읽는 향후 독자(예: `schoolcamp`/`conduct` 담당자가 `send(...)` 호출부를 만들 때)는
"`type`은 자유 문자열이고 이벤트 단위 값을 쓴다"는, 지금은 틀린 정보를 그대로 받아들일 수
있다.

이슈 기획서의 "완료 조건"이 요구하는 건 "Notion 문서에 전체 Enum 값 목록 반영"뿐이라(로컬
마스터 문서 갱신은 명시되지 않음) diff가 이 문서를 건드리지 않은 것 자체가 기획서 위반은
아니다 — 다만 로컬 마스터 기획서와 실제 구현이 어긋난 상태로 남는다는 사실은 그대로다.

**해결 방안**:
1. **이번 PR에 `1_notification-domain.md`의 해당 절(99~103번째 줄, 147번째 줄 응답 예시)을
   이번 이슈의 결론에 맞게 갱신하는 커밋을 추가한다** — 마스터 문서와 구현이 항상 일치하는
   상태를 보장하지만, 기획서가 명시한 범위(코드 4개 파일 + 이슈 기획서 1개)를 벗어나므로
   별도로 다뤄도 되는지 먼저 확인이 필요하다.
2. **후속 이슈(`outing`/`schoolcamp` 연동으로 실제 호출부가 생기는 시점)에서 한꺼번에 마스터
   문서를 갱신한다** — 지금 이슈의 범위를 늘리지 않는다는 장점이 있지만, 그 사이 마스터
   문서를 참고하는 사람이 뒤집힌 정보를 그대로 볼 위험이 남는다.
3. **지금 상태를 유지하고 이 코드 리뷰 문서를 "마스터 문서 갱신이 아직 필요하다"는 근거로만
   남겨둔다** — 추가 비용이 없지만, 문서 드리프트가 언제 해소될지 보장되지 않는다.

---

## 요약

Critical/High/Medium 없음. Low 1건 — 마스터 기획서(`1_notification-domain.md`)가 이번
이슈로 뒤집힌 "역방향 의존" 설계 근거와 이벤트 단위 예시 값을 그대로 남겨두고 있어, 이번
diff가 반영한 도메인 단위 Enum 설계와 어긋난 상태다. 코드 자체(Enum 정의, 엔티티 필드 변경,
서비스 시그니처 변경, 테스트 갱신)에는 Critical/High/Medium급 결함이 없다 — 범위·컨벤션·
스타일 대조, 다운스트림 호출부 부재 확인, `#59` 코드 리뷰에서 반영된 두 계약(FK 참조 방식,
길이 제약 Javadoc)의 회귀 여부까지 모두 확인 완료. 독립 에이전트로 돌린 교차 검증 라운드도
동일하게 findings 0건이었다.

## 반영 결과

- **Low 1**: 해결 방안 1번을 적용했다. `1_notification-domain.md`의 "도메인 모델" 절
  (`type` 필드 설명)과 엔드포인트 응답 예시를 이번 이슈의 결론(도메인 단위 Enum,
  `notification` 패키지가 자체 소유)에 맞게 갱신했다. 마스터 문서 갱신이 이번 이슈
  하나의 범위를 크게 벗어나지 않는 작은 수정이라 별도 확인 없이 바로 반영했다.
