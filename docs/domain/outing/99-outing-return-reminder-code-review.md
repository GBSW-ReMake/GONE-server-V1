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
