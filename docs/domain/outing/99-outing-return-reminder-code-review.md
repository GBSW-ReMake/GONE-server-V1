# #99 외출증 복귀 리마인더 스케줄러 — 코드 리뷰 결과

> **반영 현황(2026-09-03)**: 아래 1~5번 전부 수정 완료. `ScheduledTaskExecutor.execute()`를
> claim/handler 호출/recordSuccess 세 개의 독립된 `try/catch`로 나눠 1·2번을 고쳤고,
> `OutingService.checkAndNotifyTimeout()`(3·4번)과 `recordLocationPing()`(5번)도 각각
> 수정했다. 회귀 테스트를 `ScheduledTaskExecutorTest`/`OutingServiceTest`에 추가했고
> `./gradlew build`(테스트+체크스타일 포함)가 전부 통과함을 확인했다.

리뷰 대상: `git diff dev...feat/#99-outing-return-reminder` (병합 기준점 `d067374`),
커밋 `70d03fd`~`a630b34` 12개. 기획서 [99-outing-return-reminder.md](./99-outing-return-reminder.md)와
diff를 대조했고, 구현 과정을 모르는 상태에서 diff만 독립적으로 검토했다(컨텍스트 격리).

## 리뷰 범위/방법
- `git diff dev...feat/#99-outing-return-reminder --stat`로 변경 파일 전체를 확인하고,
  `OutingService`(`checkAndNotifyTimeout`/`recordLocationPing`/`departOuting`/`returnOuting`),
  `common/schedule/service/ScheduledTaskExecutor`/`ScheduledTaskExecutionStore`/
  `ScheduledTaskRepository`/`ScheduledTaskRunner`/`ScheduledTaskService`, `UserRoleRepository`,
  `OutingTimeoutScheduledTaskHandler`와 대응 테스트 전부를 직접 읽었다.
- `code-review` 스킬(에이전트)에 동일 diff의 별도 리뷰를 위임해 교차 검증했다 — 항목
  1번은 두 리뷰가 독립적으로 동일하게 지적했고, 3~5번은 스킬 리뷰에서 나와 코드로 직접
  재확인했다.
- `./gradlew checkstyleMain checkstyleTest`를 실행해 스타일 위반이 없음을 확인했다(`BUILD
  SUCCESSFUL`, 0 warning).
- 스코프 크립 여부: `common/schedule` 패키지를 `entity/enums/repository/service`로 재정리한
  리팩터링(커밋 `5dd754a`)은 기획서 "아직 결정 안 된 것" 절이 이 이슈의 구현/QA 단계에
  포함하기로 명시한 (a)/(b) 보류 항목 수정에 직접 딸린 변경이라 스코프 안이다. 그 외
  `ScheduledTaskController`/`ScheduledTaskAdminService`/`ScheduledTaskResponse`의 변경은
  전부 이 패키지 이동에 따른 import 경로 수정뿐이고 동작 변경이 없다. 기획서 범위를
  벗어난 변경은 발견하지 못했다.

---

### 1. 🟠 High — `claim()` 실패를 감싸는 예외 처리가 없어 한 건의 일시적 오류가 그 틱의 모든 대기 task를 건너뛰게 만든다

**문제**: 삭제된 기존 `ScheduledTaskExecutor.execute()`(`common/schedule/ScheduledTaskExecutor.java`,
diff에서 제거됨)는 `scheduledTaskRepository.findById(taskId)` 조회를 명시적으로
`try/catch`로 감싸고, 그 이유를 주석으로 남겼다: "예외를 삼키지 않으면
`ScheduledTaskRunner`의 `forEach`가 중단돼 같은 틱에서 아직 처리 안 한 나머지 task까지
이번 틱에서 스킵된다." 새 구현은 이 보호 장치를 옮기지 않았다.

`src/main/java/com/remake/gone/common/schedule/service/ScheduledTaskExecutor.java:37-38`의
`execute()`는 `executionStore.claim(taskId, now)`를 아무 `try/catch` 없이 호출한다.
`ScheduledTaskExecutionStore.claim()`(`.../service/ScheduledTaskExecutionStore.java:38-50`)은
자체 `REQUIRES_NEW` 트랜잭션 안에서 `UPDATE`(`scheduledTaskRepository.claim(...)`)와
`findById(...).orElseThrow()`를 실행하는데, 이 중 하나라도 예외를 던지면(예: DB 커넥션
풀 고갈, 커넥션 순간 끊김, 커넥션 타임아웃) 그 예외가 `execute()`를 그대로 뚫고
`ScheduledTaskRunner.run()`(`.../service/ScheduledTaskRunner.java:39-40`)의
`.forEach(taskId -> scheduledTaskExecutor.execute(...))` 람다까지 전파된다. 이 람다에도
`try/catch`가 없으므로 스트림 처리가 그 지점에서 완전히 멈추고, `findDueTaskIds`가 반환한
목록 중 아직 처리하지 못한 나머지 task는 전부 이번 틱에서 조용히 스킵된다 — 로그 한 줄
없이. 예를 들어 이번 틱에 `OUTING_TIMEOUT` task 10건이 due 상태이고 그중 3번째 task의
`claim()`이 DB 커넥션 순간 장애로 예외를 던지면, 4~10번째 task에 해당하는 학생/선생님/
DISCIPLINE 알림은 이번 틱에서 전혀 발송되지 않는다. 다음 틱(10초 뒤)에 재시도되지만,
같은 원인이 반복되는 task가 있으면(예: 특정 row의 참조 무결성 문제로 매번
`orElseThrow()`가 터지는 경우) 그 task 하나가 매 틱마다 그 뒤에 정렬된 모든 task의
처리를 영구히 막는 head-of-line blocking이 된다 — 기획서가 요구하는 "±10초 정밀도"를
그 뒤 순번의 학생/선생님 전원에 대해 무기한 어기게 된다. `ScheduledTaskExecutorTest`/
`ScheduledTaskExecutionStoreTest` 어디에도 "claim 자체가 예외를 던지는" 케이스는 없다
(둘 다 `claim()`이 0을 반환하는 경우만 검증).

**해결 방안**:
1. `ScheduledTaskExecutor.execute()`에서 `executionStore.claim(taskId, now)` 호출을
   `try/catch (Exception e)`로 감싸 로그만 남기고 조용히 반환한다(옛 코드와 동일한
   패턴). 장점: 옛 코드가 이미 검증한 패턴을 그대로 되살리는 것이라 리스크가 가장 낮고,
   변경 범위가 한 메서드로 국한된다. 단점: `claim()` 내부에서 `UPDATE`는 성공했는데
   뒤이은 `findById`만 실패하는 경우, 이미 `lastAttemptedAt`이 갱신된 채로 상태 판단
   없이 넘어가게 되는데(다음 틱이 같은 task를 다시 집어가는 데는 문제없다) 이 특수
   케이스까지 정교하게 구분하지는 못한다.
2. `ScheduledTaskRunner.run()`의 `forEach` 자체를 `try/catch`로 감싸 개별 task 처리
   실패가 전체 스트림을 죽이지 않게 한다(`forEach` 대신 명시적 `for` 루프 + 루프 안
   `try/catch`). 장점: `ScheduledTaskExecutor`에 특화되지 않은 더 일반적인 방어라
   향후 `execute()`가 다른 이유로 예외를 던지게 되어도 안전하다. 단점: 실패 지점이
   `Executor`가 아니라 `Runner`로 옮겨가, "실행/기록 실패는 Executor가 책임진다"는
   기존 클래스 역할 분리 원칙과 약간 어긋난다 — 두 군데서 방어막을 갖는 이중 방어가
   될 수 있어 방안 1과 함께 적용하는 편이 더 안전하다.

### 2. 🟡 Medium — `recordSuccess()`의 예외가 handler 실패로 오인되어 이미 성공한 알림이 재시도로 중복 발송될 수 있다

**문제**: `ScheduledTaskExecutor.execute()`(`.../service/ScheduledTaskExecutor.java:50-59`)의
`try` 블록은 `handler.handle(...)` 호출뿐 아니라 그 다음 줄의
`executionStore.recordSuccess(taskId, now, ...)` 호출까지 함께 감싼다. `recordSuccess()`는
자신만의 `REQUIRES_NEW` 트랜잭션에서 `findById` + 커밋을 수행하는 별도의 DB 왕복이라(
`.../service/ScheduledTaskExecutionStore.java`의 `recordSuccess`), `handler.handle()`이
이미 성공적으로 끝난 뒤에도 이 호출 자체가 순간적인 DB 문제로 독립적으로 실패할 수 있다.
이 경우 `catch (Exception e)` 블록이 이 실패를 "handler 실행 실패"로 착각하고
`executionStore.recordFailure(taskId, now, e.getMessage(), handler.retryPolicy())`를
호출한다 — 기록되는 에러 메시지(`e.getMessage()`)는 실제로는 `recordSuccess()`의 예외
메시지이지 handler 예외가 아니다. 더 심각한 건, `OutingTimeoutScheduledTaskHandler.handle()`이
호출하는 `checkAndNotifyTimeout()`은 이미 학생/담당 선생님/DISCIPLINE 전원에게 알림을
"성공적으로" 보낸 뒤인데도, task 상태는 여전히 `PENDING`으로 남아 `failureCount`만
증가한다 — 다음 폴링 틱(백오프 간격 이후)에 같은 task가 다시 due로 잡혀
`checkAndNotifyTimeout()`이 한 번 더 호출되고, 5분 재발송 간격과 무관하게 방금 보낸
알림과 거의 동일한 알림이 한 번 더 발송된다. 이 경로는
`ScheduledTaskExecutorTest`/`ScheduledTaskExecutionStoreTest` 어디에도 재현/검증되지
않는다.

**해결 방안**:
1. `handler.handle(...)` 호출과 `executionStore.recordSuccess(...)` 호출을 별도의
   `try/catch`로 분리한다 — `handle()`의 예외만 "handler 실패"로 취급해
   `recordFailure()`를 호출하고, `recordSuccess()` 자체의 예외는 별도로 로그만 남기고
   (task는 다음 틱에 `claim()`이 다시 시도하도록 `PENDING` 그대로 둔다) 흐름을 끝낸다.
   장점: 두 실패 원인을 정확히 구분해 오탐을 없앤다. 단점: `recordSuccess()`가 실패한
   채로 끝나면 handler는 이미 성공했는데 다음 틱에 다시 실행돼 정확히 같은 중복 발송
   문제가 재현된다 — 근본 해결이 아니라 "원인은 다르지만 결과는 같은" 상태가 된다.
2. handler가 이미 성공(부수 효과 발생)한 뒤의 기록 실패는 애초에 재시도 대상에서
   제외하도록, `recordSuccess()`가 실패하면 `recordFailure()`가 아니라 별도의
   "결과 기록 실패, 수동 확인 필요" 상태/로그로 분리한다(예: `markStuck()` 같은 새
   상태 또는 관리자 알림). 장점: 중복 알림이라는 사용자 체감 부작용을 원천 차단한다.
   단점: `ScheduledTaskStatus`에 새 상태를 추가하거나 별도 알림 채널을 만들어야 해
   #120/#126 인프라까지 건드리는 더 큰 변경이 된다 — 이 이슈 범위를 넘어설 수 있다.

### 3. 🟡 Medium — `lastLocationReminderAt`이 복귀 없이 끝나는 외출증에 대해 영구히 쌓이는 메모리 누수

**문제**: `OutingService.lastLocationReminderAt`(`src/main/java/com/remake/gone/outing/service/OutingService.java:120-124`)은
`OutingService` 싱글턴 빈의 필드인 `ConcurrentHashMap`으로, 항목 제거는 오직
`returnOuting()`(`OutingService.java:459`) 한 곳에서만 일어난다. `DEPARTED` 상태에서
`RETURNED`로 전이하는 경로는 `returnOuting()`이 유일하다 — `markSingleOutingAsMissed()`
(`OutingService.java:731`)는 `PENDING` 상태만 `MISSED`로 바꿀 뿐 `DEPARTED`는 건드리지
않는다. 따라서 학생이 출발 보고 후 위치 핑을 몇 차례 학교 반경 안에서 보내(맵에 항목이
생성됨) 끝내 도착 버튼을 누르지 않고 그 상태로 방치되는 외출증(기기 분실, 자퇴/전학 등)이
하나 생길 때마다 `Long -> LocalDateTime` 항목이 서버가 재시작하기 전까지 맵에 영구히
남는다. `TIMEOUT_REMINDER_CAP`(3시간)이 지나 `OUTING_TIMEOUT` scheduled task가 `DONE`
처리된 뒤에도, 학생 앱이 계속 위치 핑을 보내는 한 이 맵 항목은 계속 갱신되며 살아있는다.
누적 속도는 학교 운영 규모에 비례해 크지 않겠지만, 장기간 무중단 운영 시 절대 줄어들지
않는 자료구조라는 점에서 진행성 메모리 누수다.

**해결 방안**:
1. `checkAndNotifyTimeout()`이 `RETURNED_OR_MISSING`을 반환하는 시점(즉 outing이 이미
   사라졌거나 다른 경로로 종료된 시점)에도 `lastLocationReminderAt.remove(outingId)`를
   호출한다. 장점: 기존 스케줄 인프라(폴링)가 이미 주기적으로 확인하는 지점에 얹는
   것이라 새 트리거가 필요 없다. 단점: `OUTING_TIMEOUT` task 자체가 `TIMEOUT_REMINDER_CAP`
   (3시간) 이후 `DONE` 처리되어 더 이상 폴링되지 않으면, 그 이후 학생이 계속 위치 핑을
   보내는 한 여전히 정리되지 않는다 — 근본 해결이 아니라 흔한 경로(정상 미복귀 후
   타임아웃)만 줄여준다.
2. `Caffeine`/`Guava` `Cache`의 `expireAfterAccess`(예: 24시간) 같은 자동 만료 캐시로
   `ConcurrentHashMap`을 대체한다. 장점: outing 종료 경로가 무엇이든 상관없이 시간
   기반으로 확실히 정리된다 — returnOuting() 호출 누락에도 안전하다. 단점: 새 의존성
   추가가 필요할 수 있고(현재 `build.gradle`에 Caffeine이 있는지 확인 필요), 스로틀
   판단에 캐시 만료 타이밍이 끼어들 여지가 생겨(만료 직후 핑이 오면 스로틀이 리셋된
   것처럼 동작) 기존 "재시작 시 스로틀 리셋은 문제없다"는 기획서 전제와 같은 성격의
   허용 가능한 트레이드오프이지만 별도로 문서화가 필요하다.

### 4. 🟡 Medium — 담당 선생님이 `DISCIPLINE` 역할도 가지면 같은 미복귀 알림을 중복으로 받는다

**문제**: `checkAndNotifyTimeout()`(`OutingService.java:486-507`)은
`outing.getTeacher().getId()`에게 한 번(497줄), 그리고
`userRoleRepository.findUserIdsByRoleCode(DISCIPLINE_ROLE_CODE)`가 반환한 각 ID에게
또 한 번(501~505줄) "학생 미복귀 알림"을 보낸다. 두 수신자 집합 사이에 중복 제거가 없다.
`UserRoleRepository.findRoleCodesByUserId`의 Javadoc 예시(`["STUDENT", "DISCIPLINE"]`)가
보여주듯 이 시스템은 한 사용자가 여러 역할을 동시에 가질 수 있게 설계돼 있다 — 담당
선생님이 선도부(`DISCIPLINE`) 역할도 겸하는 경우가 실제로 가능하다. 이 경우 그 선생님은
`checkAndNotifyTimeout()`이 호출될 때마다(재발송 간격 5분, 최대 3시간 동안 최대 36회)
내용이 거의 동일한 "학생 미복귀 알림"을 두 번씩 받는다.

**해결 방안**:
1. DISCIPLINE 대상 목록에서 담당 선생님 ID를 걸러낸다 —
   `userRoleRepository.findUserIdsByRoleCode(DISCIPLINE_ROLE_CODE).stream()
   .filter(id -> !id.equals(outing.getTeacher().getId())).forEach(...)`. 장점: 한 줄
   수준의 최소 변경이고 의도(같은 사람에게 같은 알림을 두 번 보내지 않는다)가 코드에서
   바로 드러난다. 단점: 담당 선생님과 DISCIPLINE 목록을 합쳐 "받을 사람 집합"을 구하는
   패턴이 이후 다른 알림에서도 반복될 수 있는데, 그때마다 개별적으로 필터링을 넣어야
   한다.
2. `notificationService`에 "중복 수신자 제거 후 발송" 헬퍼(예:
   `sendToDistinctUsers(Collection<Long> userIds, ...)`)를 추가해 호출부에서 `Set`으로
   모아 한 번에 넘긴다. 장점: 이후 다른 도메인에서도 "여러 역할/여러 소스에서 모은
   수신자 목록"을 다룰 때 재사용할 수 있는 일반적인 해법이 된다. 단점:
   `NotificationService`(#59) API를 변경하는 것이라 이 이슈(#99)의 범위를 벗어난
   도메인(알림)까지 손대야 하고, 리뷰/합의가 더 필요하다.

### 5. 🟢 Low — `recordLocationPing`의 위치 기반 스로틀 확인이 get-then-put이라 동시 핑에서 중복 알림이 나갈 수 있다

**문제**: `recordLocationPing()`(`OutingService.java:543-552`)의 스로틀 판단은
`lastLocationReminderAt.get(outing.getId())`로 마지막 발송 시각을 읽고, 조건을 만족하면
알림을 보낸 뒤 `lastLocationReminderAt.put(outing.getId(), now)`로 갱신한다. `get`과
`put` 사이에 원자성이 없다 — 같은 `outingId`에 대해 두 요청이 거의 동시에 들어오면(예:
클라이언트가 네트워크 지연으로 핑을 재전송하거나, 여러 스레드가 같은 요청을 동시
처리하는 경우) 두 스레드 모두 상대방이 아직 `put`하지 않은 시점의 `lastSent` 값을 읽어
둘 다 스로틀 조건을 통과하고, 둘 다 "도착 확인" 알림을 보낸다 — 5분 스로틀이 이 좁은
경합 구간에서 무력화된다.

**해결 방안**:
1. `ConcurrentHashMap.compute(...)`로 읽기-판단-쓰기를 한 번의 원자적 연산으로
   묶는다 — 람다 안에서 스로틀 통과 여부를 계산하고 통과했을 때만 새 시각을 반환해
   맵에 반영하되, 알림 발송(`notificationService.send`)처럼 부수 효과가 있는 호출은
   `compute` 람다 밖에서 그 결과를 보고 실행한다(람다 자체는 부수 효과 없이 순수하게
   유지해야 `ConcurrentHashMap`의 계약을 지킨다). 장점: 새 의존성 없이 기존 자료구조로
   경합을 없앤다. 단점: "언제 보냈는지"와 "보낼지 말지 판단"을 분리해야 해서 코드가
   약간 더 복잡해진다.
2. `recordLocationPing()` 전체가 이미 `@Transactional`이므로, outing 단위 비관적 락이나
   DB 차원의 직렬화로 같은 outing에 대한 동시 요청 자체를 순차화한다. 장점: 스로틀
   말고도 이 메서드의 다른 동시 접근 이슈까지 함께 방어된다. 단점: 인메모리 맵 하나의
   경합을 막으려고 DB 락 비용을 들이는 것이라 과합(overkill)이고, 위치 핑처럼 빈번한
   요청 경로에 락 대기 시간을 추가하면 오히려 지연 리스크가 커진다 — 이 문제의 실제
   발생 빈도(동시 중복 요청)에 비해 비용이 크다.

---

## Critical 없음

인증/인가 우회, 데이터 손상, 트랜잭션 원자성 완전 붕괴 수준의 문제는 발견되지 않았다.
`departOuting`/`returnOuting`의 `scheduledTaskService.schedule`/`cancel` 호출이 각각의
`@Transactional` 메서드 안에서 이뤄져 도메인 상태와 `scheduled_task` 행이 실제로 같은
물리 트랜잭션으로 커밋/롤백되는 것을 코드로 확인했고(`OutingService.java:398`,
`437`의 `@Transactional`과 `413-419`, `456`의 호출 위치), `ScheduledTaskExecutor`의
claim/실행/기록 3단계 분리가 기획서가 지목한 (a)(handler 예외로 인한 markFailed 유실),
(b)(cancel과 실행 사이의 원자성 부재) 두 보류 항목을 실제로 해결했음을
`ScheduledTaskExecutorIntegrationTest`의 두 테스트로 확인했다.

---

## CodeRabbit 자동 리뷰(PR #130) 추가 지적 — 2026-09-03

PR #130에 대한 CodeRabbit 리뷰(actionable 4건 + outside-diff 1건)에서 나온 지적을
검증한 결과. **보스가 직접 수정할 예정이라 이 절은 기록용이며, 코드 변경은 아직
반영되지 않았다.**

### A. 🟡 Minor — QA 문서 케이스 6/7이 순차 호출이라 `recordLocationPing`의
`compute()` 경합을 실제로 검증하지 못함
`docs/domain/outing/99-outing-return-reminder-QA.md`의 케이스 6/7은 curl을 순차
호출한 것이라, 같은 `outingId`에 동시 요청 두 건을 보내 `ConcurrentHashMap.compute(...)`
경합 구간을 실제로 통과시키는 검증이 아니다. 새로 발견된 지적이며 타당하다 — 같은
`outingId`로 동시 요청 두 건을 보내 알림이 정확히 1건만 발송되는지 확인하는 테스트를
추가하면 닫을 수 있다.

### B. 🟠 (기존 2번의 잔여 한계) — `recordSuccess()` 실패 시 handler가 중복 실행될 수 있음
새로운 발견이 아니라, 이 문서 2번 항목의 "해결 방안 1"에 이미 적어둔 한계가 그대로
남아있다는 재확인이다. `ScheduledTaskExecutor.execute()`가 `handler.handle()`과
`recordSuccess()`를 분리한 뒤에도, **recordSuccess 자체가 실패하면 task가 `PENDING`
그대로 남아 다음 폴링 틱(10초 뒤)에 handler가 다시 호출된다** — handler(알림 발송)는
이미 성공한 뒤라 같은 알림이 한 번 더 나간다.

**완전히 막으려면 구조적으로 무엇이 필요한가**: "handler를 이미 호출했다"는 사실을
handler 호출 **전에**, `claim()`과 같은 트랜잭션 안에서 durable하게 남겨야 한다.
지금은 그 사실을 기록할 곳 자체가 없다.
- `scheduled_task`에 attempt 식별자 컬럼 추가 → 마이그레이션 필요(#120)
- `ScheduledTaskHandler` 인터페이스가 그 식별자를 받아 "이미 처리한 시도인지" 확인할
  방법 추가 → 인터페이스 계약 변경(#120)
- `NotificationService`(#59)도 같은 attempt로 이미 보낸 알림인지 dedupe해야 완전히
  막힘 → 다른 도메인까지 변경 필요

즉 스키마+인터페이스+다른 도메인까지 걸치는 변경이라 `#120` 재설계급이다. 실제
발생 조건도 "handler는 성공했는데 그 직후 `recordSuccess()`만 독립적으로 실패하는"
좁은 순간이고, 터져도 최악의 결과가 "알림이 한 번 더 감"(무한 반복 아님, 다음 틱에
정상 기록되면 종료)이라 심각도가 낮다. **후속 이슈로 분리 권장.**

### C. 🟠 (기존 3번의 잔여 한계) — cap 만료 시 `lastLocationReminderAt`이 정리 안 됨
이것도 새로운 발견이 아니라 3번 항목 "해결 방안 1"의 한계가 그대로 재확인된 것이다.
`ScheduledTaskExecutionStore.claim()`이 `task.isPastCap(now)`일 때
`handler.handle()`을 아예 호출하지 않고 `markDone()`만 하고 끝내므로,
`checkAndNotifyTimeout()` 안에 넣어둔 정리 코드가 실행될 기회 자체가 없다 — 3시간
cap을 넘기고도 위치 핑을 계속 보내는 학생이 있으면 스로틀 맵 항목이 안 지워진다.

**이건 #120을 안 건드리고도 고칠 수 있다** — 문제의 본질이 "정리 트리거를
`checkAndNotifyTimeout()` 호출 하나에만 의존한다"는 것이므로, 트리거 대신 맵 자체에
유효기간을 주면 어떤 경로로 감시가 끝나든 상관없이 정리된다. 두 방법:
1. `ConcurrentHashMap` → Caffeine 캐시(`expireAfterWrite`)로 교체. 새 의존성 추가 필요.
2. 이 프로젝트가 이미 휴대폰 인증 코드 저장에 쓰는 `common/redis`
   (`RedisRepository`/`RedisKeyType`, TTL 지원) 패턴을 그대로 재사용 — 새 의존성
   불필요, 기존 컨벤션과 일치. **추천.**

둘 다 `outing` 도메인 안에서 끝나고 `#120` 코드는 건드리지 않는다.

### D. 🟠 (신규 발견) — `compute()`로 스로틀 맵을 먼저 갱신한 뒤 알림을 보내, 트랜잭션이
롤백되면 맵만 갱신된 채로 남는다
`recordLocationPing()`은 `@Transactional`이다. `lastLocationReminderAt.compute(...)`가
먼저 실행돼 맵에 `now`를 기록한 뒤(`OutingService.java:557`), `notificationService.send(...)`가
같은 트랜잭션 안에서 알림을 저장한다(`OutingService.java:566`). 이 저장(또는 커밋
시점의 flush)이 실패해 트랜잭션 전체가 롤백되면, DB에는 알림이 안 남지만
`lastLocationReminderAt`은 이미 갱신된 채로 남는다 — 실제로는 아무것도 안 보냈는데
다음 5분간 정상 핑까지 조용히 억제된다. 이전 코드 리뷰에서는 다루지 않은, 오늘 처음
나온 유효한 지적이다.

**해결 방향**: `TransactionSynchronizationManager.registerSynchronization(...)`으로
롤백(`STATUS_ROLLED_BACK`) 시에만 그 `(outingId, now)` 항목을 되돌리는 콜백을
등록한다(`ConcurrentHashMap.remove(key, value)`의 조건부 삭제로, 그 사이 새로 들어온
갱신을 덮어쓰지 않게 한다). `outing` 도메인 안에서 끝나는 변경이다.

### 정리
| # | 항목 | #120 변경 필요? | 처리 방향 |
|---|---|---|---|
| A | QA 문서 동시성 테스트 보강 | 아니오 | **해결 완료 — 아래 참고** |
| B | recordSuccess 실패 시 중복 발송 | **아니오(재검토 결과)** | 트랜잭션 병합으로 해결 완료 — 아래 참고 |
| C | cap 만료 시 스로틀 맵 미정리 | 아니오(Redis TTL 재사용으로 해결) | **해결 완료 — 아래 참고** |
| D | 스로틀 맵 갱신-알림 저장 트랜잭션 불일치 | 아니오 | **해결 완료 — 아래 참고** |
| E | checkAndNotifyTimeout/returnOuting 경합(2026-09-04 재스캔, 신규) | 아니오 | **해결 완료 — 아래 참고** |

A/B/C/D/E 전부 해결 완료.

## E 반영 현황(2026-09-04) — 비관적 쓰기 락으로 직렬화

**문제**: `checkAndNotifyTimeout()`과 `returnOuting()`이 거의 동시에 같은 outing을 처리하면,
`checkAndNotifyTimeout()`이 잠금 없는 `findById`로 읽은 (아직 `DEPARTED`인) 스냅샷을 근거로
알림을 보내는 사이 `returnOuting()`이 `RETURNED`로 바꾸고 커밋해버릴 수 있었다. `Outing`의
`@Version`은 `checkAndNotifyTimeout()`이 Outing을 갱신하지 않아(읽기만 함) 이 경합을 감지
못한다.

**해결**: `UserRepository.findByIdForUpdate`(#29, 외출증 신청 겹침 검사에 쓰던 기존
패턴)와 동일한 방식으로 `OutingRepository`에 `findByIdForUpdate`/`findByCodeForUpdate`
(둘 다 `@Lock(PESSIMISTIC_WRITE)`)를 추가했다. `checkAndNotifyTimeout()`은
`findByIdForUpdate`로, `returnOuting()`은 `findByCodeForUpdate`로 조회를 바꿔, 두 메서드가
같은 outing 행을 두고 경합하면 뒤에 들어온 트랜잭션이 앞선 트랜잭션의 커밋을 기다리게
했다 — 어느 쪽이 먼저 락을 잡든, 나머지 하나는 항상 최신 상태를 보고 진행한다.

**검증**: `OutingTimeoutReturnRaceIntegrationTest`(신규)로 이 잠금 메커니즘 자체를 실 DB로
검증했다 — 한 트랜잭션이 `findByIdForUpdate`로 락을 잡고 있는 동안 다른 트랜잭션의
`findByCodeForUpdate`가 실제로 블록됐다가 앞 트랜잭션 커밋 후에야 진행되는지 이벤트 순서로
확인한다. 서비스 메서드 두 개를 직접 동시 호출하는 방식은 두 트랜잭션이 정확히 겹치는
순간을 결정론적으로 재현할 수 없어(스레드 스케줄링 의존) 채택하지 않았다 — 두 메서드가
공유하는 잠금 메커니즘 자체를 검증하는 것으로 충분하다고 판단했다. `./gradlew build`
전체(테스트+체크스타일 포함) 통과 확인.

## B 반영 현황(2026-09-04) — #120 스키마 변경 없이 트랜잭션 병합으로 해결

위 "완전히 막으려면 구조적으로 무엇이 필요한가" 절은 **claim/handle/recordSuccess 3단계
분리를 그대로 유지한 채** 고치려 할 때의 결론이었다. 재검토 결과, `handler.handle()`의
부수 효과가 현재는 `NotificationService.send()`(인앱 DB INSERT뿐, FCM 없음)로 **순수
DB 쓰기**라는 전제 하에서는, 그 구조 자체를 바꿔 스키마 변경 없이 해결할 수 있다는 게
드러났다.

**해결 방식**: `ScheduledTaskExecutionStore`에 `executeAndRecordSuccess(handler, claimed,
taskId, now)`를 신규 추가해 `handler.handle()` 호출과 성공 기록(`markDone`/`markSucceeded`)을
**하나의 `REQUIRES_NEW` 트랜잭션**으로 묶었다(`ScheduledTaskExecutor`는 이 메서드 하나만
호출하도록 단순화). `handler.handle()`이 호출하는 도메인 서비스(`checkAndNotifyTimeout`
등)는 기본 전파(`REQUIRED`)라 이 트랜잭션에 참여할 뿐 독립적으로 커밋하지 않으므로, 그
안에서 저장한 알림도 이 메서드가 끝까지 정상 반환해 커밋될 때만 함께 확정된다. 성공 기록
단계에서 예외가 나면(또는 handler 자신이 예외를 던지면) 트랜잭션 전체가 롤백돼 알림 저장도
함께 취소되므로, "알림은 이미 나갔는데 기록만 실패해 다음 틱에 중복 발송"되는 중간 상태
자체가 생기지 않는다.

**검증**: `ScheduledTaskExecutorTest.recordsFailureWhenRecordingSucceedsAfterHandleThrows`
(성공 기록 단계 실패가 handler 실패와 동일하게 재시도 대상으로 분류되는지),
`ScheduledTaskExecutionStoreTest.ExecuteAndRecordSuccess`(분기 로직 단위 검증),
`ScheduledTaskExecutorIntegrationTest.rollsBackHandlerSideEffectWhenExecutionFailsAfterSideEffect`
(실제 `@Transactional(REQUIRED)` 참여 호출로 남긴 부수 효과 행이 실패 시 실제로 롤백되는지
실 DB로 확인)로 검증했다. `./gradlew build` 전체(테스트+체크스타일 포함) 통과 확인.

**남는 전제**: 이 보장은 handler의 부수 효과가 이 물리 트랜잭션 안에서 끝나는 DB 쓰기일
때만 유효하다. 알림 도메인에 FCM 푸시(외부 API 호출)가 추가되면 그 호출은 트랜잭션
롤백으로 되돌릴 수 없으므로, 그 시점에는 이 방식만으론 부족해지고 원래 검토했던 attempt
식별자(#120 스키마 변경) 방식이 다시 필요해진다 — 그때 재검토.

## C/D 반영 현황(2026-09-04) — 인메모리 맵을 Redis TTL 쿨다운으로 교체

C(cap 만료 시 스로틀 맵 미정리)와 D(스로틀 맵 갱신-알림 저장 트랜잭션 불일치)를 같이
해결했다 — 둘 다 원인이 "인메모리 `ConcurrentHashMap`이 트랜잭션/폴링 생애주기와 무관하게
독립적으로 관리된다"는 같은 지점이라, `lastLocationReminderAt` 맵 자체를 걷어내고 이미
휴대폰 인증 쿨다운(`PHONE_SEND_COOLDOWN`)에 쓰던 `RedisRepository.saveIfAbsent(...)`(원자적
SETNX+TTL) 패턴으로 교체했다.

**C 해결**: `RedisKeyType.OUTING_LOCATION_REMINDER_COOLDOWN`(TTL 5분)을 신규 추가하고,
`recordLocationPing()`의 `compute()` 블록을 `redisRepository.saveIfAbsent(...)` 호출로
대체했다. 어떤 경로로 감시가 끝나든(정상 복귀, `checkAndNotifyTimeout`의 감지, 또는 cap
초과로 handler 자체가 더 이상 안 불리는 경우까지) TTL이 5분 뒤 자동으로 키를 지우므로,
"핸들러가 다시 안 불려서 정리 코드가 실행될 기회가 없는" 경로가 있어도 항목이 영구히
남지 않는다. `returnOuting()`/`checkAndNotifyTimeout()`의 명시적 `redisRepository.delete(...)`
호출은 TTL 만료를 기다리지 않고 즉시 정리하기 위한 것으로 남겨뒀다(필수는 아니고 TTL이
최종 안전망).

**D 해결**: `recordLocationPing()`이 `saveIfAbsent(...)`로 쿨다운 키를 저장한 뒤 알림을
같은 `@Transactional` 트랜잭션 안에서 저장하는데, `TransactionSynchronizationManager
.registerSynchronization(...)`로 트랜잭션이 롤백됐을 때만(`STATUS_ROLLED_BACK`) 그
쿨다운 키를 되돌리는 콜백을 등록했다. `isSynchronizationActive()` 가드는 트랜잭션 프록시
없이 서비스 메서드를 직접 호출하는 단위 테스트를 위한 것이고, 실제 호출 경로(컨트롤러 →
`@Transactional` 프록시)에서는 항상 활성 상태다.

**검증**: `OutingServiceTest.RecordLocationPing`의 관련 테스트를 mock `RedisRepository`
기반으로 재작성했다(`givenCooldownAvailable()`/`doesNotNotifyWhenCooldownIsActive()`/
`clearsCooldownWhenTimeoutCheckReturnsReturnedOrMissing()`). 실제 TTL 만료 자체는 단위
테스트로 재현할 수 없어(실 Redis 필요) `PHONE_SEND_COOLDOWN`과 동일한 기존 컨벤션대로
단위 테스트 범위를 "서비스가 `saveIfAbsent`/`delete` 반환값·호출을 올바르게 반영하는지"로
좁혔다. `./gradlew build` 전체(테스트+체크스타일 포함) 통과 확인.

## A 반영 현황(2026-09-04) — 실 Redis 동시성 통합 테스트로 해결

C/D를 고치며 스로틀 자료구조가 `ConcurrentHashMap.compute(...)`에서 Redis
`saveIfAbsent`(원자적 SETNX+TTL)로 바뀌었으므로, A가 원래 지적한 "QA 문서 케이스 6/7이
순차 호출이라 경합 구간을 검증 못 함" 문제도 이 시점에 같이 닫을 수 있었다.
`OutingLocationReminderConcurrencyIntegrationTest`(신규,
`SchoolCampSessionClaimServiceIntegrationTest`와 동일한 `ExecutorService`+
`CountDownLatch` 패턴)를 추가해 실 DB + 실 Redis로 같은 외출증에 동시 요청 20건을 보내고
`notificationRepository.findByUserId(...)`로 알림이 정확히 1건만 저장됐는지 확인했다 —
mock 기반 단위 테스트로는 검증 불가능한 실제 원자성 보장을 실 인프라로 증명한다.
`OutingServiceTest`의 mock 기반 단위 테스트만으로는 이 경합 자체를 검증할 수 없다는
한계는 여전하지만(반환값 분기만 검증), 이 통합 테스트가 그 공백을 메운다. QA 문서
`99-outing-return-reminder-QA.md`에도 반영 내역을 남겼다.
