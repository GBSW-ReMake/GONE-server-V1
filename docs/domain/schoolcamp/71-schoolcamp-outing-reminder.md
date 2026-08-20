# #71 스쿨캠핑 외출 연동 리마인더 스케줄러 — 기획서

관련 이슈: [#71 스쿨캠핑 외출 연동 리마인더 스케줄러 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/71)
마스터 기획서: [1_schoolcamp-domain.md](./1_schoolcamp-domain.md)의 "외출 도메인 연동 —
리마인더" 절
선행 이슈: [#68](./68-schoolcamp-application.md)(신청 API, 완료·머지됨 — 이 이슈가 의존하는
`SchoolCampApplication`/`SchoolCampMember`를 만들었다), [#70](./70-schoolcamp-cancel-update.md)
(취소/수정, 완료·머지됨 — `SchoolCampMemberRepository.findByApplicationId`를 이 이슈에서
그대로 재사용한다, 아래 참고)

## 개요/목적
매일 08:30(KST)에 실행되는 스케줄러가, 오늘 스쿨캠핑에 참여하는 학생 중 아직 점심(`LUNCH`)
시간대 외출증을 신청하지 않은 사람에게 "장 보러 갈 외출증은 받으셨나요?" 알림을 보낸다.
새 HTTP 엔드포인트는 없다 — `outing`의 `OutingMissedScheduler`(#42)와 같은 `@Scheduled`
백그라운드 컴포넌트 1개가 산출물이다.

**선행 조건 공백 발견(검토 필요)**: 이슈 본문이 "학생이 알림을 실제로 받아보는 조회 API
(`GET /api/v1/notifications`, #37)는 아직 컨트롤러가 없다"고 명시했는데, 실제로 확인해보니
**이슈 #37 자체가 GitHub에 존재하지 않는다**(`gh issue view 37` → "Could not resolve to an
issue"). `docs/domain/notification/37-notification-inbox.md` 기획서 파일은 리포에 남아있지만
그 기획서가 가리키는 이슈도, `NotificationController`도 실제로는 없다 — 알림 저장(#59)까지만
됐고, 조회 API는 이슈조차 없는 완전한 공백이다. 이 이슈는 발송(저장)까지만 다루므로 기술적으로
구현 자체는 가능하지만, **알림이 저장돼도 학생이 확인할 방법이 없는 상태로 머지된다**는 점은 확인·인지됐다. **결정
(2026-08-18)**: "알림 조회 API"는 별도 이슈로 나중에 만들기로 하고, 이 이슈를 막지
않는다 — 이 이슈는 발송(저장)까지만 완결하면 되는 것으로 승인한다.

**모델 정정 참고**: 이슈 본문은 "오늘 날짜의 `SchoolCampSession`에 신청한 **모든**
`SchoolCampApplication` 조회"라고 쓰여 있는데, #67/#68에서 확정된 "하루 세션에 팀 1개만
가능" 모델상 이 조회는 항상 결과가 0건 또는 1건이다(여러 건이 나올 수 없음) — 로직 자체는
그대로 두되(쿼리는 여전히 리스트로 받는 게 자연스럽다), 아래 구현에서 "여러 건 처리"를 특별히
최적화할 필요가 없다는 점만 명확히 한다.

## 스케줄러 설계

### `SchoolCampReminderScheduler` (신규, `schoolcamp.scheduler` 패키지)
마스터 기획서 판단 그대로 `schoolcamp` 패키지에 둔다("스쿨캠핑이 외출을 알아야 하는 것"이지
반대는 아님). `outing`/`notification` 두 도메인의 리포지토리·서비스를 참조하는 코드다.

```java
package com.remake.gone.schoolcamp.scheduler;

@Component
@RequiredArgsConstructor
public class SchoolCampReminderScheduler {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final SchoolCampService schoolCampService;

  /** 매일 08:30(KST)에 1회 실행됩니다. */
  @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Seoul")
  public void remindOutingForTodayCampers() {
    schoolCampService.sendOutingReminders(LocalDate.now(KST));
  }
}
```
- `OutingMissedScheduler`(#42)와 같은 얇은 컴포넌트 — 시각만 구해 서비스에 위임한다. 실제
  로직은 단위 테스트에서 임의의 `today`를 주입할 수 있도록 `SchoolCampService`(도메인
  서비스, 이 이슈의 실질적 구현 대상)에 둔다.
- `fixedDelay`(#42, 1분 주기 폴링)가 아니라 **`cron`**을 쓴다 — 이 기능은 "하루 1번, 정확히
  08:30에"라는 고정 시각 요구사항이라 주기적 폴링이 아니라 크론 표현식이 맞다(이 프로젝트에서
  `cron` 기반 `@Scheduled`를 쓰는 첫 사례).

### `SchoolCampService.sendOutingReminders(LocalDate today)` (신규 메서드)
```java
@Transactional
public void sendOutingReminders(LocalDate today) {
  applicationRepository.findBySession_CampDateAndCancelledAtIsNull(today)
      .forEach(application -> remindMembers(application, today));
}

private void remindMembers(SchoolCampApplication application, LocalDate today) {
  memberRepository.findByApplicationId(application.getId()).stream()
      .filter(member -> member.getStudentUser() != null) // "기타" 자유 입력 팀원은 계정이 없어 알림 대상 아님
      .forEach(member -> remindIfNoLunchOuting(member.getStudentUser().getId(), today));
}

private void remindIfNoLunchOuting(Long studentId, LocalDate today) {
  boolean hasLunchOuting = outingRepository.existsByStudentIdAndOutingDateAndTimeSlotAndStatusIn(
      studentId, today, OutingTimeSlot.LUNCH, OutingStatus.ACTIVE_STATUSES);
  if (!hasLunchOuting) {
    notificationService.send(studentId, "오늘 스쿨캠핑이 있어요!",
        "장 보러 갈 외출증은 받으셨나요? 점심시간에 미리 신청해보세요.",
        NotificationType.SCHOOLCAMP);
  }
}
```
- **알림 대상은 "그날 참여하는 전원"**이다(대표 신청자만이 아니라 `SchoolCampMember`
  전체) — 이슈 본문 "각 신청 학생마다"를 팀원 단위로 해석했다. 누가 실제로 장을 보러 가는지
  구분하는 데이터가 없어, 안내가 필요 없을 수 있는 팀원에게도 갈 수 있지만(과다 알림 쪽으로
  치우침), 반대로 필요한 사람이 못 받는 것보다는 안전한 방향이라 판단했다.
- **가입 안 된 "기타" 팀원은 알림 대상에서 제외**(계정이 없어 보낼 곳이 없음) — 담당 선생님도
  같은 이유로 이 알림 대상이 아니다(마스터 기획서에 선생님 알림 언급 없음, 학생 대상 리마인더
  전용).
- `OutingStatus.ACTIVE_STATUSES`(`PENDING`/`APPROVED`/`DEPARTED`)를 재사용 — 아직 승인 안
  됐어도(`PENDING`) 이미 신청은 한 상태이므로 리마인더 대상에서 제외한다(승인 여부가 아니라
  "신청 여부"가 이 알림의 목적). `REJECTED`는 포함하지 않아 다시 알림 대상이 된다(재신청을
  유도하는 게 자연스럽다는 판단).
- **왜 `outing`(#42)처럼 건별 독립 트랜잭션으로 안 쪼개는지**: #42는 스케줄러의 낡은
  스냅샷이 승인/거절과 동시에 커밋되며 서로 덮어쓸 위험이 있어 건별 트랜잭션 + 낙관적 락으로
  분리했다. 이 스케줄러는 기존 데이터를 수정하지 않고 **읽기만 하고 새 `Notification` 행을
  추가만** 한다 — 같은 행을 두고 경합할 다른 트랜잭션이 없으므로 그런 위험 자체가 없다. 대상
  인원도 하루 최대 8명으로 상한이 명확해, 하나의 트랜잭션으로 처리해도 실패 시 함께 롤백되는
  게 오히려 "일부만 알림 받음" 같은 불완전한 상태를 피할 수 있어 낫다.

### `OutingRepository`에 추가할 조회 메서드
```java
/**
 * 특정 학생이 특정 날짜의 특정 시간대에, 주어진 상태들에 해당하는 외출증을 신청했는지
 * 확인합니다(#71 스쿨캠핑 리마인더 — 이미 신청했으면 리마인더 대상에서 제외).
 */
boolean existsByStudentIdAndOutingDateAndTimeSlotAndStatusIn(
    Long studentId, LocalDate outingDate, OutingTimeSlot timeSlot,
    Collection<OutingStatus> statuses);
```

### `SchoolCampApplicationRepository`에 추가할 조회 메서드
(엔티티 자체는 #68에서 생겼지만, 아래 메서드는 이 이슈에서 처음 필요해져 추가한다)
```java
// SchoolCampApplicationRepository
List<SchoolCampApplication> findBySession_CampDateAndCancelledAtIsNull(LocalDate campDate);
```
`SchoolCampMemberRepository.findByApplicationId(Long applicationId)`는 이미 #70에서
추가돼 있어(팀원 목록 조회용, diff 계산에 사용) 새로 추가할 메서드 없이 그대로
재사용한다.

## 영향 받는 기존 코드
- 신규: `schoolcamp/scheduler/SchoolCampReminderScheduler`
- 수정: `SchoolCampService`(`sendOutingReminders` 추가, `OutingRepository` 의존성 신규
  추가 — `NotificationService`는 #68부터 이미 주입돼 있어 추가할 게 없다),
  `SchoolCampApplicationRepository`(조회 메서드 추가), `OutingRepository`(조회 메서드 추가)
- `SchoolCampMemberRepository`는 수정 없음(`findByApplicationId`를 #70에서 추가된 그대로
  재사용)
- 신규 마이그레이션 없음(기존 테이블만 조회)
- 신규 에러 코드 없음(HTTP 엔드포인트가 아니라 배치 작업이라 `ErrorCode` 체계 밖)
- `@EnableScheduling`은 이미 `GoneServerV1Application`에 있어(#42에서 활성화) 추가 설정 불필요

## 리스크 및 고려사항
- **API 설계 6원칙 체크**: 이 이슈는 HTTP 엔드포인트가 없어 6원칙이 직접 적용되지 않는다 —
  대신 배치 작업 관점에서 "한 가지를 잘하기"(리마인더 발송 하나)와 "확장성/성능"(대상 인원
  상한 8명, N+1 우려 없음)만 남긴다.
- **알림 조회 API 공백**(위 "선행 조건 공백" 참고) — 이 이슈만으로는 학생이 실제로 이 알림을
  볼 방법이 없다. 기능적으로는 완결되지만 사용자 가치로 이어지려면 별도 이슈가 필요하다.
- **점심시간(12:30) 이후 신청하는 경우를 놓친다** — 리마인더는 08:30 1회뿐이라, 그 이후에
  외출증을 신청해도(또는 08:30 이전엔 없었지만 이후 새로 생겨도) 리마인더는 다시 가지 않는다.
  이슈 본문/마스터 기획서 모두 "1일 1회(확정)"라고 명시했으므로 의도된 동작이다.
- **오늘 세션이 없으면(스쿨캠핑 없는 날) 조용히 아무 일도 하지 않는다** — 별도 로그/알림 없이
  정상 종료. `OutingMissedScheduler`도 대상이 없으면 같은 방식으로 동작한다.
- **알림 발송 실패는 그대로 예외 전파**(`NotificationService` 기존 정책) — 한 트랜잭션이라
  한 학생 알림 저장이 실패하면 이미 저장하려던 다른 학생 알림도 함께 롤백된다. 스케줄러는
  다음 날 08:30에나 다시 도니 재시도가 없다는 뜻이지만, 발생 확률(DB 저장 실패)이 낮고
  발생하면 어차피 로그로 드러나 수동 대응이 가능하다고 보고 이 이슈에서 재시도 로직을 넣지
  않는다.

## 테스트
- `SchoolCampService.sendOutingReminders`:
  - 오늘 세션 없음 → 아무 알림도 발송하지 않음
  - 오늘 세션은 있지만 신청(취소 안 된 `SchoolCampApplication`)이 없음 → 발송 없음
  - 팀원 중 이미 `LUNCH` 외출증(`PENDING`/`APPROVED`/`DEPARTED`)이 있는 학생 → 그 학생에게는
    발송 안 함
  - 팀원 중 `LUNCH` 외출증이 없는 학생 → 발송함(`NotificationService.send` 호출 검증)
  - `REJECTED`/`MISSED` 상태의 `LUNCH` 외출증만 있는 학생 → 다시 발송 대상(제외되지 않음)
  - "기타"(자유 입력, `studentUser == null`) 팀원 → 발송 대상에서 제외
  - 취소된 신청(`cancelledAt` 있음)은 조회 자체에서 제외되는지
- `SchoolCampReminderScheduler`: `LocalDate.now(KST)`를 구해 `sendOutingReminders`에 그대로
  위임하는지만 확인(`OutingMissedScheduler` 테스트와 동일한 얇은 검증 수준)
