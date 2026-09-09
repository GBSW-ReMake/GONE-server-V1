# 잡 러너(scheduled_task) 아키텍처 가이드 — #120 인프라 + #99 outing 접점

이 문서는 정식 기획서/QA/코드 리뷰 문서가 아니다. `common/schedule` 패키지(#120)와 그 위에
얹힌 outing 접점(#99)이 실제로 어떻게 동작하는지, 코드를 처음부터 끝까지 따라가며 이해할
수 있도록 정리한 설명 문서다. 대응하는 정식 기획서는
[120-common-scheduled-task-infra.md](./120-common-scheduled-task-infra.md),
[99-outing-return-reminder.md](../outing/99-outing-return-reminder.md)에 있다 —
"무엇을 왜 그렇게 설계했는가"의 결정 기록은 그쪽에 있고, 이 문서는 "실제 코드가 어떻게
움직이는가"에 집중한다.

## 1. 이 인프라가 푸는 문제

"N분/N시간 뒤에 특정 도메인 엔티티 하나를 다시 확인해서, 아직도 조건을 만족하면 부수
효과(주로 알림)를 실행하고, 조건이 사라지거나 상한 시간을 넘기면 그만둔다" — 이 패턴이
outing(#99, 복귀 리마인더) 말고도 여러 도메인에서 반복될 걸로 예상되어, 도메인 무관한
공용 폴링 인프라로 뽑아낸 것이 `common/schedule` 패키지(#120)다.

Quartz/JobRunr 같은 외부 스케줄러 라이브러리 대신 자체 테이블 폴링 방식을 택한 이유,
다중 인스턴스를 고려하지 않은 이유 등 "왜 이 방식인가"는 #120 기획서의 대안 비교 절에
있다 — 여기서는 이미 그 결론(=`scheduled_task` 테이블 기반 폴링) 위에서 실제 구현이
어떻게 짜여 있는지만 다룬다.

## 2. 레이어 구조 한눈에 보기

```
common/schedule/
├── entity/ScheduledTask.java          — 예약 1건 = 테이블 row 1개. 상태 전이 로직 전부 여기.
├── enums/ScheduledTaskStatus.java     — PENDING / DONE / FAILED
├── repository/ScheduledTaskRepository.java — claim/findDueTaskIds 등 원자적 쿼리
└── service/
    ├── ScheduledTaskService.java      — 도메인 서비스가 쓰는 공개 API: schedule()/cancel()
    ├── ScheduledTaskRunner.java       — 10초마다 깨어나는 폴링 트리거 (@Scheduled)
    ├── ScheduledTaskExecutor.java     — task 1건을 claim→실행→기록 3단계로 처리
    ├── ScheduledTaskExecutionStore.java — 그 3단계 각각의 트랜잭션 경계
    ├── ScheduledTaskHandler.java      — 도메인이 구현하는 인터페이스 (접점)
    ├── RetryPolicy.java               — 실패 시 재시도 횟수/백오프 정책
    └── ScheduledTaskAdminService.java — 관리자 모니터링/재시도(#126, 이 문서 범위 밖)

outing/schedule/
└── OutingTimeoutScheduledTaskHandler.java — outing 쪽에서 위 인터페이스를 구현한 접점
```

역할을 한 문장씩으로 압축하면:
- **`ScheduledTaskService`**: 도메인 서비스가 "이거 나중에 확인해줘"/"이제 그만 확인해도 돼"를
  말하는 창구.
- **`ScheduledTaskRunner`**: 10초마다 "지금 확인해야 할 게 뭐 있지?"라고 묻는 시계.
  실행/상태 변경은 전혀 하지 않는다.
- **`ScheduledTaskExecutor`**: 그 목록의 ID 하나를 받아 실제로 "확인하고 필요하면 실행"하는
  일꾼.
- **`ScheduledTaskExecutionStore`**: 그 일꾼이 DB 상태를 읽고 쓰는 세 지점(claim/성공 기록/
  실패 기록) 각각을 독립 트랜잭션으로 열어주는 협력자.
- **`ScheduledTaskHandler`**: outing/추후 다른 도메인이 "실제로 뭘 확인하고 뭘 실행할지"를
  채워 넣는 빈칸.

## 3. 예약 1건(`ScheduledTask`)이 들고 있는 값

```java
new ScheduledTask(taskType, referenceId, scheduledAt, interval, cap);
```

| 필드 | 의미 |
|---|---|
| `taskType` | 어떤 도메인 로직인지 구분하는 문자열(`"OUTING_TIMEOUT"`). 핸들러 빈 이름과 정확히 일치해야 한다. |
| `referenceId` | 그 도메인 엔티티의 PK(outing이면 `outing.getId()`). |
| `scheduledAt` | 최초 확인 예정 시각. |
| `intervalSeconds` | 성공 실행 후 몇 초 뒤에 다시 확인할지. `null`이면 1회성 작업. |
| `endAt` | `scheduledAt + cap` — 이 시각을 넘기면 handler가 아직 "끝났다"고 안 해도 강제 종료. |
| `nextAttemptAt` | 폴링이 실제로 비교하는 값. "지금이 이 시각을 지났으면 확인 대상." |
| `failureCount` / `lastError` | 연속 실패 횟수와 마지막 에러 메시지. |
| `status` | `PENDING`(대기/재시도 중) / `DONE`(종료) / `FAILED`(재시도 상한 초과, 수동 개입 필요). |

`nextAttemptAt`이 핵심이다. `ScheduledTaskRunner`는 오직 "`status=PENDING`이고
`nextAttemptAt <= 지금`인 행"만 골라온다(`findDueTaskIds`). "몇 번째 실행인지",
"다음엔 언제 확인할지" 같은 계산은 전부 `ScheduledTask` 자신의 메서드
(`markSucceeded`/`markFailed`/`markDone`)가 스스로 갱신한다 — Runner/Executor는
그 계산 로직을 전혀 모른다.

## 4. 실제 흐름 — outing 시나리오로 처음부터 끝까지

학생이 13:40 마감인 외출증으로 13:00에 출발 보고를 했다고 하자.

### 4.1 등록 (`departOuting`)
```java
// OutingService.departOuting()
scheduledTaskService.schedule(
    "OUTING_TIMEOUT", outing.getId(),
    LocalDateTime.of(outing.getOutingDate(), outing.getEndTime()), // 13:40
    Duration.ofMinutes(5),   // 재발송 간격
    Duration.ofHours(3));    // 발송 상한
```
`departOuting()`은 이미 `@Transactional`이고, `schedule()`도 `@Transactional`
(기본 전파 `REQUIRED`)이라 **같은 물리 트랜잭션에 참여한다** — outing 상태 변경과
`scheduled_task` row 삽입이 한 커밋으로 함께 성공하거나 함께 롤백된다. 이 원자성이
없으면 "출발 처리는 됐는데 리마인더 등록은 실패" 같은 반쪽 상태가 생길 수 있다.

이 시점에 `scheduled_task` row 하나가 `nextAttemptAt=13:40`, `status=PENDING`으로
만들어진다. 13:00~13:40 사이에는 Runner가 매 틱 조회해도 이 행은 `nextAttemptAt`
조건에 안 걸려 그냥 지나간다.

### 4.2 폴링 (`ScheduledTaskRunner.run()`, 10초마다)
13:40:00이 지나면 다음 틱(예: 13:40:10)에서 `findDueTaskIds`가 이 행의 ID를 반환한다.
```java
scheduledTaskRepository.findDueTaskIds(PENDING, tickStartedAt)
    .forEach(taskId -> scheduledTaskExecutor.execute(taskId, LocalDateTime.now(KST)));
```
한 틱에 due 건이 여러 개면 이 `forEach`가 하나씩 순서대로 `execute()`를 호출한다 —
이 "순서대로, 한 건씩"이라는 성질이 뒤에서 다룰 버그(claim 예외 전파)와 직결된다.

### 4.3 claim (`ScheduledTaskExecutor.execute()` 1단계)
```java
ScheduledTaskExecutionStore.ClaimedTask claimed = executionStore.claim(taskId, now);
```
`claim()` 내부:
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public ClaimedTask claim(Long taskId, LocalDateTime now) {
  if (scheduledTaskRepository.claim(taskId, PENDING, now) == 0) {
    return null; // 이미 취소됐거나 다른 상태로 바뀜
  }
  ScheduledTask task = scheduledTaskRepository.findById(taskId).orElseThrow();
  if (task.isPastCap(now)) {
    task.markDone(now);
    return null; // cap 넘겼으면 handler도 안 부르고 바로 종료 처리
  }
  return new ClaimedTask(task.getTaskType(), task.getReferenceId(), task.isOneShot());
}
```
`scheduledTaskRepository.claim(...)`은 `UPDATE ... WHERE id=? AND status=PENDING`
단일 SQL이다. "조회 후 상태 확인 후 별도 UPDATE"가 아니라 **조건부 UPDATE 한 방**이라,
"학생이 마침 이 순간 도착 버튼을 눌러 `returnOuting()`의 `cancel()`이 이 행을 지우는
것"과 이 폴링의 claim이 동시에 벌어져도 경합이 생기지 않는다 — `cancel()`의 DELETE가
먼저 커밋됐으면 이 UPDATE는 대상 행이 없어 0건을 갱신하고, `claim()`은 `null`을
반환해 handler를 호출하지 않는다. 이게 "#99 코드 리뷰 보류 항목 (b): cancel과 실행
사이 원자성"을 해결한 방식이다.

### 4.4 handler 호출 (`OutingTimeoutScheduledTaskHandler`)
```java
ScheduledTaskHandler handler = handlers.get(claimed.taskType()); // "OUTING_TIMEOUT" → 이 빈
boolean done = handler.handle(claimed.referenceId());            // outing.getId() 전달
```
```java
@Override
public boolean handle(Long outingId) {
  OutingService.TimeoutCheckResult result =
      outingService.checkAndNotifyTimeout(outingId, LocalDateTime.now(KST));
  return result == OutingService.TimeoutCheckResult.RETURNED_OR_MISSING;
}
```
`OutingTimeoutScheduledTaskHandler`는 그냐 어댑터다 — 실제 판단(아직 `DEPARTED`인지,
알림을 누구에게 보낼지)은 전부 `OutingService.checkAndNotifyTimeout()`(순수 도메인
메서드, 스케줄링 인프라를 전혀 모름)이 한다. 이 메서드는 학생/담당 선생님/DISCIPLINE
역할 전원에게 `NotificationService.send(...)`를 호출하고, 아직 `DEPARTED`면
`CONTINUE`를, 이미 사라졌거나 복귀됐으면 `RETURNED_OR_MISSING`을 반환한다.
`handle()`은 이 결과를 인프라가 이해하는 `boolean`(더 실행할 필요 있는지)으로 그대로
번역할 뿐이다.

### 4.5 결과 기록 (`ScheduledTaskExecutor.execute()` 3단계)
- `done=false`(아직 `DEPARTED`, 계속 감시)면 →
  `executionStore.recordSuccess(taskId, now, false)` →
  `task.markSucceeded(now)` → `nextAttemptAt = now + 5분`. 다음 틱들은 이 새
  `nextAttemptAt`을 지날 때까지 이 행을 지나친다.
- `done=true`(학생이 그 사이 도착 처리됐거나 outing이 사라짐)면 →
  `recordSuccess(taskId, now, true)` → `markDone(now)` → `status=DONE`, 더 이상
  폴링 대상이 아님.
- `handler.handle()`이 예외를 던지면 → `recordFailure(...)` →
  `markFailed(...)` — `failureCount`를 늘리고 지수 백오프로 `nextAttemptAt`을 미룬다.
  기본 정책(`RetryPolicy.DEFAULT`)은 5회 실패하면 `FAILED`로 격리해 더 이상 폴링되지
  않게 한다(관리자가 #126 API로 수동 재시도 가능).

3시간(cap) 동안 이 4.2~4.5가 5분 간격으로 계속 반복되다가, 학생이 도착 버튼을 누르면
`returnOuting()`의 `scheduledTaskService.cancel("OUTING_TIMEOUT", outing.getId())`가
이 행을 지워 폴링 대상에서 사라진다. 또는 3시간을 넘기면 4.3의 `isPastCap()`이
`markDone()`으로 조용히 종료시킨다.

## 5. 왜 claim/실행/기록을 물리적으로 다른 트랜잭션 3개로 쪼갰나

`ScheduledTaskExecutor` 자신은 `@Transactional`이 **아니다**. 대신 claim/
recordSuccess/recordFailure 각각이 `ScheduledTaskExecutionStore`에서
`@Transactional(propagation = REQUIRES_NEW)`로 따로 열린다. 처음엔 `execute()`
전체가 하나의 트랜잭션이었는데(#120/#126 코드 리뷰 시점), 이렇게 바꾼 이유는 두 가지다.

1. **self-invocation 문제**: `claim`/`recordSuccess`/`recordFailure`를
   `ScheduledTaskExecutor`의 private 메서드로 두면, 같은 빈 안에서 `this.claim(...)`
   처럼 호출하는 self-invocation이 되어 Spring AOP 프록시를 거치지 않는다 —
   `@Transactional`을 붙여도 무시된다. 그래서 별도 빈(`ScheduledTaskExecutionStore`)으로
   분리해야 각 메서드가 실제로 독립 트랜잭션을 연다.
2. **handler 예외가 기록 자체를 집어삼키는 문제**: `execute()`가 통째로 한
   트랜잭션이던 시절엔, `handler.handle()` 안에서 호출하는 도메인 서비스의
   `@Transactional(REQUIRED)` 메서드가 예외를 던지면 그 실패가 바깥 트랜잭션
   전체를 rollback-only로 표시했다. 그러면 catch 블록에서 실패를 기록하려는
   `markFailed()` 호출까지 커밋 시점에 `UnexpectedRollbackException`으로 함께
   유실됐다 — "실패했다"는 사실 자체가 기록되지 않는 셈. 세 단계를 별도 물리
   트랜잭션으로 쪼개면, handler 내부 실패는 그 순간 열려 있던 (도메인 서비스의)
   트랜잭션만 롤백시킬 뿐 — `claim`이 이미 커밋한 것도, 뒤이어 `recordFailure`가
   새로 여는 트랜잭션도 그와 무관하다.

이 두 문제 다 "#99 코드 리뷰 보류 항목 (a)"로 남아있다가 이번(#99) 구현에서 실제로
재현하고 고친 것이다.

## 6. 오늘(2026-09-03) 코드 리뷰에서 고친 5가지 — 경계가 왜 중요한지 보여주는 실례

방금 위 구조를 마무리한 직후 진행한 9단계 코드 리뷰
([99-outing-return-reminder-code-review.md](../outing/99-outing-return-reminder-code-review.md))에서
5가지가 지적되어 모두 수정했다. 이 인프라를 이해하는 데 좋은 반례라 같이 남겨둔다.

1. **claim 자체의 예외를 안 막았던 문제**: 4.3의 `claim()` 호출을 `try/catch` 없이
   두면, DB 순간 장애 하나가 4.2의 `forEach` 전체를 중단시켜 그 틱의 나머지 due
   task를 전부 조용히 스킵시킨다 — "한 건씩 순서대로" 처리한다는 성질이 오히려
   전체 정지의 원인이 될 수 있다는 걸 보여준다. `execute()`에 `try/catch`를
   추가해 한 건의 claim 실패가 다른 건에 전혀 영향을 안 주게 고쳤다.
2. **recordSuccess 실패가 handler 실패로 오인된 문제**: `handler.handle()`과
   `recordSuccess()`를 같은 `try` 블록에 두면, handler는 이미 성공(알림 발송까지
   끝남)했는데 그 다음 `recordSuccess()` 자체가 실패해도 catch 블록이 "handler가
   실패했다"고 착각해 재시도를 예약한다 → 다음 틱에 같은 알림이 또 나간다. 두
   호출을 분리된 `try/catch`로 나눠, 각 단계의 실패를 각자의 원인으로만 처리하게
   고쳤다.
3. **`lastLocationReminderAt`(위치 기반 리마인더 스로틀 맵) 메모리 누수**: 이건
   `common/schedule` 밖, `OutingService`의 인메모리 상태다. `returnOuting()`에서만
   지우고 있었는데, 복귀 없이 끝나는 outing(자퇴 등)이 있으면 그 항목이 영구히
   남는다. `checkAndNotifyTimeout()`이 `RETURNED_OR_MISSING`을 반환하는 시점에도
   같이 지우도록 고쳤다.
4. **담당 선생님이 DISCIPLINE 역할도 겸하면 중복 알림**: `checkAndNotifyTimeout()`이
   선생님과 DISCIPLINE 목록에 중복 제거 없이 각각 알림을 보내던 것 — 두 집합이
   겹칠 수 있다는 걸 놓친 경우. 필터링으로 고쳤다.
5. **위치 핑 스로틀의 get-then-put 경합**: `Map.get()` → 판단 → `Map.put()`이
   원자적이지 않아 동시 핑 두 건이 서로의 갱신을 못 보고 둘 다 통과할 수 있던 것.
   `ConcurrentHashMap.compute(...)`로 판단+갱신을 한 번에 묶어 고쳤다.

## 7. 코드를 직접 읽는다면 이 순서를 추천

1. `ScheduledTask.java` — 상태와 전이 규칙부터. `markSucceeded`/`markFailed`/
   `markDone`의 주석이 "왜 이렇게 계산하는지"를 설명한다.
2. `ScheduledTaskRepository.java` — `claim`/`findDueTaskIds` 쿼리. SQL이 원자성을
   어떻게 보장하는지가 여기 다 있다.
3. `ScheduledTaskExecutionStore.java` → `ScheduledTaskExecutor.java` — 위 5장에서
   설명한 3단계 분리를 코드로 확인.
4. `ScheduledTaskRunner.java` — 트리거는 정말 단순하다는 걸 확인(계산 로직이 하나도
   없다).
5. `ScheduledTaskService.java` — 도메인 쪽에서 보는 공개 API.
6. `OutingTimeoutScheduledTaskHandler.java` → `OutingService.checkAndNotifyTimeout`/
   `departOuting`/`returnOuting`/`recordLocationPing` — outing이 이 인프라를 실제로
   어떻게 쓰는지.
7. 테스트: `ScheduledTaskExecutorTest`(claim/기록 분기), `ScheduledTaskExecutionStoreTest`
   (트랜잭션 없는 순수 로직), `ScheduledTaskExecutorIntegrationTest`(실제 트랜잭션
   전파 검증), `OutingTimeoutScheduledTaskHandlerTest`, `OutingServiceTest`의
   `CheckAndNotifyTimeout`/`RecordLocationPing` 중첩 클래스.

## 8. 새 도메인이 이 인프라를 재사용하려면

기획서(#99)의 "#102와의 범위 구분" 절이 예시로 들었듯, 새 감시 대상이 생기면:
1. `task_type` 문자열을 하나 정한다(예: `"OUTING_DEPART_TIMEOUT"`).
2. `ScheduledTaskHandler`를 구현하고 `@Component("그 문자열")`로 등록한다 — Spring이
   `Map<String, ScheduledTaskHandler>` 자동 주입으로 알아서 찾아준다.
3. 도메인 서비스의 `@Transactional` 메서드 안에서 `scheduledTaskService.schedule(...)`/
   `cancel(...)`을 호출한다.
그 외 `common/schedule` 쪽 코드는 전혀 건드릴 필요가 없다 — 이게 이 인프라를 공용으로
뽑아낸 목적이다.
