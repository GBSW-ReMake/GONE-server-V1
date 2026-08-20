# #71 스쿨캠핑 외출 연동 리마인더 스케줄러 — 코드 리뷰

관련 이슈/기획서: [71-schoolcamp-outing-reminder.md](./71-schoolcamp-outing-reminder.md)

## 리뷰 범위
- 대상 diff: `git diff dev...HEAD` (base `dev` → `feat/#71-schoolcamp-outing-reminder`)
- 대상 커밋 4개(기획서 커밋 `a8c1d6d`는 문서만이라 제외):
  - `f2a592e` feat(schoolcamp): #71 리마인더용 리포지토리 조회 메서드 추가
  - `c4e9d82` feat(schoolcamp): #71 리마인더 발송 서비스 로직 구현
  - `de06df0` feat(schoolcamp): #71 외출 연동 리마인더 스케줄러 추가
  - `5a63335` test(schoolcamp): #71 리마인더 스케줄러/서비스 단위 테스트 추가
- 변경 파일: `OutingRepository.java`, `SchoolCampApplicationRepository.java`,
  `SchoolCampReminderScheduler.java`(신규), `SchoolCampService.java`,
  `SchoolCampReminderSchedulerTest.java`(신규), `SchoolCampServiceTest.java` — 기획서 범위를
  벗어난 변경 없음(신규 HTTP 엔드포인트/에러코드/마이그레이션 없음, 기획서와 동일).
- 리뷰 방법: 코드 정적 분석(diff 전문 확인, 관련 엔티티/기존 리포지토리 메서드와의 정합성 대조).
  실서버 기동/DB 검증은 하지 않음(QA 단계로 이관).

## 기획서-구현 일치 확인
아래 4개 핵심 결정 사항 모두 구현과 일치한다.
- **리마인더 대상 = 팀원 전체(계정 있는 학생만)**: `SchoolCampService.remindMembers`가
  `memberRepository.findByApplicationId`로 전체 팀원(대표 신청자 포함, `SchoolCampMember`
  엔티티 주석상 대표자도 `applicant=true`인 행으로 포함됨)을 조회하고
  `member.getStudentUser() != null`로 필터링한다 — "기타" 게스트(계정 없음) 제외 확인.
- **LUNCH 판단 기준 = `OutingStatus.ACTIVE_STATUSES`**: `remindIfNoLunchOuting`이
  `existsByStudentIdAndOutingDateAndTimeSlotAndStatusIn(..., OutingTimeSlot.LUNCH,
  OutingStatus.ACTIVE_STATUSES)`를 그대로 사용 — `PENDING/APPROVED/DEPARTED`만 "신청함"으로
  간주, `REJECTED`는 포함되지 않아 재알림 대상이 되는 설계와 일치.
- **취소된 신청은 조회에서 제외**: `SchoolCampApplicationRepository.
  findBySessionCampDateAndCancelledAtIsNull`이 `cancelledAtIsNull` 조건을 포함 — 기획서의
  `findBySession_CampDateAndCancelledAtIsNull`과 밑줄 유무만 다르고(Spring Data 프로퍼티
  탐색으로 둘 다 유효), 오히려 기존 `findBySessionIdInAndCancelledAtIsNull`(밑줄 없음)과 더
  일관된 네이밍이라 문제 아님.
- **하나의 트랜잭션으로 처리**: `sendOutingReminders`에만 `@Transactional`이 있고,
  `NotificationService.send`는 자체 트랜잭션이 없어(`@Transactional` 미부착) 호출자 트랜잭션에
  합류한다 — 건별 독립 트랜잭션이 아니라 스케줄러 1회 실행 = 트랜잭션 1개로 확인.

## 발견 사항

### 1. 🟠 High — `CUSTOM` 시간대 외출증이 점심을 포함해도 리마인더가 잘못 발송됨

**문제**: `remindIfNoLunchOuting`(`SchoolCampService.java:373`)은
`existsByStudentIdAndOutingDateAndTimeSlotAndStatusIn(studentId, today, OutingTimeSlot.LUNCH,
ACTIVE_STATUSES)`로 "이미 신청했는지"를 판단하는데, 이 조건은 저장된 `timeSlot` 값이 정확히
`LUNCH`인 행만 찾는다. 그런데 `OutingTimeSlot`(`OutingTimeSlot.java:13-21`)의 `CUSTOM` 허용
범위는 `CUSTOM_WINDOW_START(08:40)`~`CUSTOM_WINDOW_END(20:30)`로, `LUNCH` 프리셋 범위
(`12:30`~`13:40`)를 완전히 포함한다. 즉 학생이 `CUSTOM` 시간대로 예를 들어 11:00~15:00 같은
외출증을 신청하면(점심시간을 포함해서 실제로 장을 보러 갈 수 있는 시간대인데도) `timeSlot`이
`CUSTOM`으로 저장되므로 위 쿼리는 `false`를 반환한다. 그 결과 실제로는 점심시간을 포함하는
외출증을 이미 신청한 학생에게도 "장 보러 갈 외출증은 받으셨나요?" 리마인더가 잘못 발송된다.
기획서(`71-schoolcamp-outing-reminder.md`)의 리스크 절 어디에도 이 케이스는 다루지 않았다 —
"과다 알림 쪽으로 치우침"이라는 기존 설계 판단은 "누가 실제로 장을 보러 가는지 알 수 없다"는
데이터 공백 때문이지, "이미 그 시간대를 포함하는 외출증이 있는데도 놓친다"는 이번 케이스와는
원인이 다르다(전자는 의도된 트레이드오프, 후자는 판단 로직 자체의 결함).

**해결 방안**:
1. 시간대 이름이 아니라 실제 시간 겹침으로 판단하도록 쿼리/로직을 바꾼다 — 예를 들어
   `existsByStudentIdAndOutingDateAndStatusInAndOutingStartTimeLessThanEqualAndOutingEndTime
   GreaterThanEqual` 같은 겹침 조건, 혹은 `OutingTimeSlot.LUNCH.getStartTime()/getEndTime()`과
   실제 저장된 시작/종료 시각을 비교하는 방식으로 바꾼다. 기존 `outing` 도메인의 중복 신청
   검사(`OutingService`의 겹침 검증) 로직과 판단 기준이 통일돼 가장 정확하지만, 겹침 조건을
   리포지토리 메서드 이름/JPQL로 표현해야 해서 구현 비용이 더 든다.
2. 우선 `timeSlot in (LUNCH, CUSTOM)`으로 조회 조건을 넓히고 애플리케이션 레벨에서 `CUSTOM`
   행만 실제 시작/종료 시각으로 점심시간 포함 여부를 다시 걸러낸다 — 리포지토리 쿼리 하나로
   해결하려 하지 않고 서비스 레이어에서 필터링을 추가하는 절충안이라 비용은 낮지만, 로직이
   두 곳(쿼리 + 서비스)에 걸쳐 있어 다음에 시간대 규칙이 바뀔 때 놓치기 쉽다.
3. (범위를 좁히는 대안) 이 이슈에서는 손대지 않고 "`CUSTOM`으로 점심을 포함한 학생은 리마인더를
   중복으로 받을 수 있다"를 기획서 리스크 절에 알려진 제약으로 명시만 하고 넘어간다 — 비용은
   0이지만, 실제로 매일 실행되는 배치라 이 케이스에 해당하는 학생은 매번 잘못된 알림을 받게
   되므로 임시방편에 가깝다.

### 2. 🟡 Medium — REJECTED/MISSED 리마인더 테스트가 실제로는 그 상태를 검증하지 않음

**문제**: `SchoolCampServiceTest.SendOutingReminders.remindsMemberWhoseOnlyOutingIsInactive`
(`src/test/java/com/remake/gone/schoolcamp/service/SchoolCampServiceTest.java:976-991`)는
"REJECTED/MISSED 상태의 외출증만 있는 학생도 다시 리마인더 대상"이라는 이름을 달고 있지만,
바로 위 `remindsMemberWithoutLunchOuting`(:961-975)과 스텁·검증이 완전히 동일하다 — 둘 다
`outingRepository.existsByStudentIdAndOutingDateAndTimeSlotAndStatusIn(...)`이 `false`를
반환하도록만 스텁하고, 실제로 REJECTED/MISSED 상태의 `Outing` 엔티티를 만들거나 리포지토리
쿼리에 넣어보는 부분이 없다. 서비스 코드는 "exists가 false면 보낸다"만 알 뿐 왜 false인지는
모르므로 서비스 단위 테스트로는 원천적으로 두 시나리오를 구분할 수 없다 — 즉 이 테스트는 실제로
새로운 것을 검증하지 않고 이름만 다른 중복 테스트다. 테스트 코드의 주석(`// ACTIVE_STATUSES
(PENDING/APPROVED/DEPARTED)만 조회 조건으로 넘기므로, REJECTED/MISSED만 있는 학생은 리포지토리
스텁이 자연히 false를 반환한다`)도 이를 인지하고 있다. 이 리포지토리 프로젝트에는
`@DataJpaTest` 같은 리포지토리 레벨 테스트 선례가 없어(`OutingRepository`/
`SchoolCampApplicationRepository` 모두 테스트 파일 없음), "REJECTED는 정말 리마인더 대상에서
빠지지 않는가"라는 핵심 로직이 어느 테스트에서도 실제 데이터로 검증되지 않는다. 기획서(테스트
절 5번째 항목)가 이 시나리오를 별도 테스트로 명시했는데, 구현은 이름만 채우고 실제 검증은
비어 있는 상태다.

**해결 방안**:
1. `OutingRepository`에 `@DataJpaTest` 기반 리포지토리 테스트를 새로 추가해
   `existsByStudentIdAndOutingDateAndTimeSlotAndStatusIn`이 REJECTED/MISSED 상태의
   `Outing`을 실제로 제외하고 PENDING/APPROVED/DEPARTED만 히트하는지 DB 레벨에서 검증한다 —
   가장 확실하게 이 이슈의 핵심 로직(상태 필터링)을 실제로 확인할 수 있지만, 이 프로젝트에
   리포지토리 레벨 테스트 선례가 없어 새 테스트 패턴/픽스처를 도입하는 비용이 든다.
2. 중복 테스트를 지우거나(`remindsMemberWithoutLunchOuting`과 통합), `DisplayName`과 위치를
   "서비스는 exists 결과가 false이기만 하면 이유를 묻지 않고 리마인더를 보낸다"로 정직하게
   재작성해 실제로 검증하는 범위만 주장하게 한다 — 비용은 거의 없지만, REJECTED/MISSED가
   정말 제외되는지는 여전히 자동 테스트로 뒷받침되지 않아 회귀 시 못 잡는다(수동 QA로 별도
   확인 필요).

### 3. 🟢 Low — 새 Javadoc의 이슈 번호 표기가 기존 컨벤션과 다름

**문제**: 이번 diff의 새 Javadoc 4곳 모두 이슈 번호를 `{@code #71}`로 감싸서 표기한다
(`OutingRepository.java:70`, `SchoolCampApplicationRepository.java:44`,
`SchoolCampReminderScheduler.java:12`, `SchoolCampService.java:352`). 반면 기존 코드베이스는
`OutingService.java`, `JwtProvider.java`, `OutingQueryPeriodResolver.java` 등 전반에서 이슈
번호를 그냥 괄호로만 표기한다(`(#41)`, `(#42)`, `(#52)` 등, `{@code}` 없이). 기능에는 영향
없지만 같은 저장소 안에 두 가지 표기 관행이 섞이게 된다.

**해결 방안**:
1. 새 Javadoc 4곳의 `{@code #71}`을 `#71`(괄호 표기, `{@code}` 제거)로 통일한다 — 기계적인
   찾아바꾸기로 비용이 거의 없고, 기존 관례와 완전히 일치시킬 수 있다.
2. 그대로 둔다 — 렌더링된 Javadoc에서 이슈 번호가 고정폭 글꼴로 강조되는 효과는 있지만, 다음
   PR들이 어느 쪽을 따라야 할지 기준이 모호해져 표기가 더 갈라질 위험이 있다.

## Critical 없음
Critical 등급으로 분류할 문제는 발견되지 않았다(데이터 손상·보안·인가 우회 없음). 확인한 범위:
신규 리포지토리 메서드의 Spring Data 프로퍼티 경로가 실제 엔티티 필드(`Outing.student`→
`studentId`, `SchoolCampApplication.session.campDate`, `cancelledAt`)와 정확히 일치하는지,
트랜잭션 경계가 기획서대로 단일 트랜잭션인지, `NotificationType.SCHOOLCAMP`/제목·본문 길이
제약 준수 여부, 게스트 팀원 제외·대표 신청자 포함 로직, 스케줄러가 얇은 위임 컴포넌트로만
구현됐는지, 기존 `OutingRepository.findByStudentIdAndOutingDateAndStatusIn` 등과의 네이밍
일관성, KST 타임존 처리 일관성.
