# #99 외출증 복귀 리마인더 스케줄러 (알림 도메인 연동) — 기획서

관련 이슈: [#99 외출증 복귀 리마인더 스케줄러 (알림 도메인 연동)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/99)
선행 이슈: [#43 출발/도착 보고](./43-outing-depart-return.md), [#97 위치 핑/동선 조회](./97-outing-location.md),
[#59 알림 저장 모듈](../notification/59-notification-core.md),
[#120 범용 이벤트 스케줄링 인프라](../common/120-common-scheduled-task-infra.md)(머지 완료,
아래 "선행 이슈 완료 확인" 참고), [#126 모니터링/재시도 API](../common/126-common-scheduled-task-monitoring.md)(머지 완료)

> **v5(2026-09-01, 보스 지시로 범위 분리) → v6(2026-09-01, #120/#126 머지 완료 반영)**:
> v4까지는 이 문서 안에 `scheduled_task` 테이블/엔티티/리포지토리/`ScheduledTaskHandler`/
> `ScheduledTaskService`/`ScheduledTaskRunner`(공용 폴링 인프라) 설계가 통째로 들어 있었다.
> 이 인프라는 outing 전용이 아니라 여러 도메인이 공유할 공용 인프라라 **별도 이슈
> [#120](https://github.com/GBSW-ReMake/GONE-server-V1/issues/120)으로 분리**했고, 이후
> 관리자 모니터링/재시도 API가 **[#126](https://github.com/GBSW-ReMake/GONE-server-V1/issues/126)로
> 추가 분리**됐다 — 둘 다 실제로 구현/머지됐다(아래 "선행 이슈 완료 확인" 참고, 인용하는
> 시그니처는 실제 코드로 재검증 완료). 이 문서(#99)는 그 인프라 위에서 동작하는 **outing
> 도메인 접점만** 남긴다: 시간 초과 감지 핸들러(`OutingTimeoutScheduledTaskHandler`),
> `departOuting`/`returnOuting` 연동, 위치 기반 복귀 감지(이벤트 기반, 인프라와 무관).
> v4까지 있던 대안 비교(JobRunr/Quartz/Redis 기각 사유 등)는 인프라 자체의 설계 근거이므로
> #120 기획서로 이관했다 — 이 문서에는 그 결론만 전제로 남긴다: **"`scheduled_task` 테이블
> 기반 범용 스케줄러(#120) 위에서 outing 접점을 구현한다."**

## 개요/목적
마스터 기획서의 "복귀 리마인더" 절이 정의한 두 조건 —
(1) 위치는 학교 안인데 도착 버튼을 안 누름, (2) 종료 시각이 지났는데 아직 `DEPARTED`—
을 실제로 감지해서 `NotificationService.send(...)`(#59, 인앱 저장만 — FCM은 알림 도메인
2단계로 아직 없음, 이 이슈 범위 밖)를 호출하는 백그라운드 로직을 구현한다. 새 엔드포인트는
없다.

**전제(변경 없음, 보스 확인 2026-08-28)**:
- **정밀도**: ±10초 내외. 복귀 마감을 넘긴 순간과 알림 발송 사이 지연이 이 범위 안이어야 한다.
- **다중 인스턴스**: 전환 계획 없음(현재 시점 기준). 나중에 바뀌면 재검토.
- 이 두 값이 바뀌면 #120 인프라의 폴링 주기/claim 로직 설계도 다시 검토해야 한다.

## 1. 시간 초과 감지 — outing 접점 (`OutingTimeoutScheduledTaskHandler`)
`scheduled_task` 테이블/`ScheduledTaskHandler` 인터페이스/`ScheduledTaskService`/
`ScheduledTaskRunner`(폴링 루프)는 **#120에서 구현하는 공용 인프라**다. 이 이슈는 그
인터페이스를 구현하는 outing 쪽 접점만 다룬다.

### `OutingTimeoutScheduledTaskHandler` (신규, `outing/schedule`) — outing 도메인 접점
```java
package com.remake.gone.outing.schedule;

@Component("OUTING_TIMEOUT")
@RequiredArgsConstructor
public class OutingTimeoutScheduledTaskHandler implements ScheduledTaskHandler {

  private final OutingService outingService;

  @Override
  public boolean handle(Long outingId) {
    OutingService.TimeoutCheckResult result =
        outingService.checkAndNotifyTimeout(outingId, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    return result == OutingService.TimeoutCheckResult.RETURNED_OR_MISSING;
  }
}
```
`ScheduledTaskHandler`는 #120이 정의하는 인터페이스(`boolean handle(Long referenceId)`)를
그대로 구현한다. `@Component("OUTING_TIMEOUT")`처럼 `task_type` 문자열을 빈 이름으로
등록하면 #120의 `ScheduledTaskRunner`가 `Map<String, ScheduledTaskHandler>` 자동 주입으로
찾아 호출한다(자세한 매핑 방식은 #120 기획서 참고).

`checkAndNotifyTimeout`, `TimeoutCheckResult`는 스케줄러 방식과 무관한 순수 도메인
로직이다 — 아래 서명만 재확인.
```java
public enum TimeoutCheckResult { RETURNED_OR_MISSING, CONTINUE }

@Transactional
public TimeoutCheckResult checkAndNotifyTimeout(Long outingId, LocalDateTime now) {
  Optional<Outing> found = outingRepository.findById(outingId);
  if (found.isEmpty() || found.get().getStatus() != OutingStatus.DEPARTED) {
    return TimeoutCheckResult.RETURNED_OR_MISSING; // 이미 도착 처리됨 — 감시 종료
  }
  Outing outing = found.get();
  User student = outing.getStudent();
  notificationService.send(student.getId(),
      "외출 시간이 지났습니다",
      "예정된 복귀 시각이 지났습니다. 빨리 복귀해서 '도착' 버튼을 눌러주세요.",
      NotificationType.OUTING);
  notificationService.send(outing.getTeacher().getId(),
      "학생 미복귀 알림",
      student.getGbsw().getName() + " 학생이 아직 복귀하지 않았습니다 (외출증 " + outing.getCode() + ").",
      NotificationType.OUTING);
  userRoleRepository.findUserIdsByRoleCode("DISCIPLINE").forEach(disciplineUserId ->
      notificationService.send(disciplineUserId,
          "학생 미복귀 알림",
          student.getGbsw().getName() + " 학생이 아직 복귀하지 않았습니다 (외출증 " + outing.getCode() + ").",
          NotificationType.OUTING));
  return TimeoutCheckResult.CONTINUE;
}
```

### `departOuting`/`returnOuting` 연동
- `departOuting`의 `saveOrRejectAsAlreadyProcessed(outing)` 직후, **같은 트랜잭션 안에서**:
  `scheduledTaskService.schedule("OUTING_TIMEOUT", outing.getId(),
  LocalDateTime.of(outing.getOutingDate(), outing.getEndTime()), Duration.ofMinutes(5),
  Duration.ofHours(3))` 호출(`ScheduledTaskService`는 #120이 제공하는 공용 빈).
- `returnOuting`의 상태 변경 직후, **같은 트랜잭션 안에서**:
  `scheduledTaskService.cancel("OUTING_TIMEOUT", outing.getId())` 호출 — outing과
  `scheduled_task`가 같은 DB의 같은 트랜잭션이라 원자적으로 함께 커밋/롤백된다(자세한
  원자성 근거는 #120 기획서 참고).
- `OutingService` 생성자(Lombok `@RequiredArgsConstructor`)에 `ScheduledTaskService` 필드
  추가.

## 2. 위치 기반 복귀 감지 (이벤트 기반, 스케줄링 인프라 불필요 — #120과 무관)
`OutingService.recordLocationPing`(#97, 기존 메서드) 안에서 저장 직후에 검사를 추가한다.
핑이 올 때 반경만 확인하면 되고, 핑이 없으면 애초에 "아직 안 돌아온" 상태이므로 알림을
못 보내도 논리적 공백이 생기지 않는다.

```java
// recordLocationPing 안, outingLocationRepository.save(...) 다음
if (GeoUtils.distanceMeters(request.latitude(), request.longitude(),
        outingProperties.schoolLatitude(), outingProperties.schoolLongitude())
    <= outingProperties.schoolRadiusMeters()) {
  LocalDateTime lastSent = lastLocationReminderAt.get(outing.getId());
  if (lastSent == null || Duration.between(lastSent, now).compareTo(LOCATION_REMINDER_INTERVAL) >= 0) {
    notificationService.send(studentUserId,
        "도착 확인이 필요해요",
        "학교 반경 안에 계신 것 같아요. '도착' 버튼을 눌러주세요.",
        NotificationType.OUTING);
    lastLocationReminderAt.put(outing.getId(), now);
  }
}
```
- `lastLocationReminderAt`(`ConcurrentHashMap<Long, LocalDateTime>`, `OutingService` 필드,
  `LOCATION_REMINDER_INTERVAL = Duration.ofMinutes(5)`)로 5분 스로틀만 걸면 된다.
- `returnOuting`에서 `lastLocationReminderAt.remove(outing.getId())`로 정리한다.
- 재시작으로 이 맵이 초기화돼도 문제없다 — 최악의 경우 재시작 직후 핑 한 번에 대해 스로틀이
  리셋되어 알림이 한 번 더 갈 뿐이다.

## 신규 리포지토리 메서드
`UserRoleRepository.findUserIdsByRoleCode(String roleCode)` → `List<Long>`(신규).
```java
@Query("select ur.user.id from UserRole ur where ur.role.code = :roleCode")
List<Long> findUserIdsByRoleCode(@Param("roleCode") String roleCode);
```

## 데이터 모델 변경
`Outing` 엔티티 자체는 변경 없음. `scheduled_task` 테이블은 #120에서 추가한다 — 이 이슈
자체의 마이그레이션은 없다.

## 영향 받는 기존 코드
- `build.gradle`/`application.yml`: 변경 없음
- 신규 마이그레이션: 없음(`scheduled_task` 테이블은 #120)
- 신규: `outing/schedule/OutingTimeoutScheduledTaskHandler`(공용 인터페이스 `common/schedule/
  ScheduledTaskHandler`는 #120에서 제공)
- `OutingService`: `checkAndNotifyTimeout`(신규), `recordLocationPing`(위치 반경 체크
  추가), `departOuting`(`scheduledTaskService.schedule(...)` 호출 추가), `returnOuting`
  (`scheduledTaskService.cancel(...)` 호출 추가), `ScheduledTaskService`(#120 제공)와
  `NotificationService`(#59 제공, `checkAndNotifyTimeout`/`recordLocationPing`이 호출)
  의존성 추가. 현재 `OutingService` 생성자는 `outingRepository`/`outingLocationRepository`/
  `userRepository`/`userRoleRepository`/`r2FileService`/`outingProperties` 6개를
  받는다(실제 코드 확인) — 이 두 필드를 추가하면 8개가 된다.
- `NotificationService`: 변경 없음
- `UserRoleRepository`: `findUserIdsByRoleCode(String roleCode)`(신규)
- `OutingRepository`: 변경 없음
- `1_outing-domain.md`: "복귀 리마인더" 절 정정 주석은 이미 반영됨

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 새 엔드포인트 없음, 해당 없음.
2. **빠르게 시작하기**: 해당 없음(백그라운드 로직).
3. **일관성**: 스케줄러는 트리거만, 로직은 서비스라는 기존(#42) 컨벤션을 그대로 따른다.
4. **의미 있는 오류**: 신규 에러 코드 없음.
5. **확장성/성능**: 정밀 건별 스케줄링(#120 인프라)이라 DEPARTED 건수가 늘어나도 불필요한
   순회가 없다. 폴링 주기/트랜잭션 분리 등 인프라 자체의 확장성 판단은 #120 기획서에서
   다룬다.
6. **하위 호환성**: 기존 API 요청/응답 스키마 변경 없음.

## 알려진 제약 (경미, 의도된 트레이드오프)
- **폴링 지연/트랜잭션 길이/다중 인스턴스 중복 실행/실패 처리·백오프**: #120 인프라 자체의
  제약이라 그 기획서에서 다룬다.
- **과거 시각으로 스케줄될 때의 동작**: #120의 `findDueTasks` 조건에 자연히 걸려 다음
  폴링 틱에 바로 처리된다(#120 기획서 참고).
- **#102와의 범위 구분**: `APPROVED` 상태에서 출발조차 안 하고 마감된 케이스(#102, 별도
  이슈)는 이 이슈 범위 밖이다. 같은 #120 `ScheduledTaskHandler` 인터페이스를 재사용할
  예정이며, `task_type`을 `OUTING_DEPART_TIMEOUT` 등으로 새로 등록하는 핸들러 컴포넌트
  하나만 추가하면 된다.
- **FCM 미연동**: 인앱 알림 저장까지만 한다.

## 아직 결정 안 된 것 (리뷰 필요)
- **위치 핑 침묵 감지 (범위 미확정)**: "DEPARTED 상태에서 핑이 일정 횟수 이상 안 오면
  선생님/선도부에게 '위치 추적이 끊겼습니다' 알림"이라는 아이디어. 데드맨 스위치 패턴(핑
  올 때마다 `scheduledAt`을 갱신해 다음 침묵-감지 시점을 다시 예약, 안 오면 그대로 발동)으로
  #120 인프라를 그대로 재사용해 구현 가능하나, **이 이슈에 포함할지 별도 이슈로 뺄지 아직
  정하지 않았다.**
- **위치 핑 전송 주기 불일치**: `#43` 기획서는 "1분으로 확정"이라 적혀 있는데, 논의 중
  "10초 단위로 보낸다"는 얘기가 나왔다. 실제 앱 구현 값이 뭔지 확인이 필요하다.
- 재발송 간격(5분)/발송 상한(종료 후 3시간) — 가정값 유지, 운영해보고 조정 가능.
- 알림 문구 최종본 — sentence-refinement.md 기준으로 다듬을 수 있다.
- **`ScheduledTaskExecutor`의 트랜잭션 분리/원자적 claim 보류 항목(#120/#126 코드
  리뷰)**: handler 실행과 실패 기록이 같은 물리 트랜잭션을 공유하는 문제, `cancel()`과
  `execute()` 사이 원자적 보호 없음 문제 둘 다 "실제 handler가 없어 재현 불가능"이라는
  이유로 이 이슈에서 같이 고치기로 보류돼 있었다(#99 이슈 코멘트 참고). 이제
  `OutingTimeoutScheduledTaskHandler`를 실제로 구현하므로, 이 이슈의 구현/QA 단계에서
  두 항목을 재현·수정해야 한다 — 8단계 구현 범위에 포함한다.

## 선행 이슈 완료 확인
#120(범용 이벤트 스케줄링 인프라)이 2026-09-01 머지 완료됐다(PR #123). #126(모니터링/재시도
API)도 같은 날 머지 완료(PR #127). 이 이슈는 이제 바로 착수 가능하다.

## 테스트 방법
1. 학생 로그인 → 마감이 1~2분 뒤인 `CUSTOM` 시간대로 신청 →
   선생님 승인 → 학생이 출발 보고
2. 마감 시각이 지나도록 대기 → 학생/담당 선생님/DISCIPLINE 계정 각각에 알림이 저장됐는지
   확인(알림 도메인 조회 API 또는 `SELECT * FROM notification`), 걸린 시간이 ±10초 안인지 확인
3. 5분 더 대기 → 같은 알림이 한 번 더 저장됐는지 확인(재실행 동작 확인)
4. 이 상태에서 학생이 도착 보고 → `scheduled_task`에서 해당 row가 삭제됐는지 확인
   (`cancel` 호출 확인), 이후로는 더 이상 알림이 추가되지 않는지 확인
5. 학교 반경 안에서 위치 핑을 연속 전송 → 학생에게 "도착 확인" 알림이 5분 간격으로만 오는지
   확인
6. 단위 테스트: `OutingServiceTest`에 `checkAndNotifyTimeout`(CONTINUE/RETURNED_OR_MISSING),
   `recordLocationPing`의 반경 내 알림 발송/스로틀 케이스, `departOuting`/`returnOuting`이
   `ScheduledTaskService.schedule`/`cancel`을 호출하는지 검증(모킹) 추가.
   `OutingTimeoutScheduledTaskHandlerTest`(신규) — `checkAndNotifyTimeout` 결과를
   `handle`의 boolean 반환값으로 올바르게 매핑하는지 검증.
   (`ScheduledTaskServiceTest`/`ScheduledTaskRunnerTest`는 #120 범위)
7. `ScheduledTaskExecutor` 보류 항목 재현·수정(#120/#126 코드 리뷰에서 넘어온 것):
   (a) `OutingTimeoutScheduledTaskHandler.handle()` 안에서 던진 예외가 `execute()`의
   `REQUIRES_NEW` 트랜잭션을 rollback-only로 만드는지, 그로 인해 `markFailed()` 기록이
   유실되는지 실제로 재현하고 트랜잭션 분리로 고친다. (b) `returnOuting()`의 `cancel()`과
   폴링이 동시에 같은 task를 처리할 때 취소된 task의 `handle()`이 그래도 실행되는지
   재현하고 원자적 claim으로 고친다.

## 리스크 및 고려사항
- 위 "알려진 제약" 절 참고.
- `OutingService`가 이미 커진 상태라, 이번에 추가되는 필드/메서드가 더해지면 분리를 고려할
  시점이 될 수 있다 — 이번 이슈 범위에서는 분리하지 않고 향후 리팩터링 백로그로 남긴다.
