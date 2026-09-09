# #141 ScheduledTaskExecutor 핸들러 미등록 시 무한 재시도/로그 스팸 — 기획서

관련 이슈: [#141 fix(common): ScheduledTaskExecutor 핸들러 미등록 시 무한 재시도/로그 스팸](https://github.com/GBSW-ReMake/GONE-server-V1/issues/141)
계기: PR #140 Copilot 리뷰([`ScheduledTaskExecutor.java:59`](../../../src/main/java/com/remake/gone/common/schedule/service/ScheduledTaskExecutor.java#L59))
선행 이슈: [#120 범용 이벤트 스케줄링 인프라](./120-common-scheduled-task-infra.md),
[#126 스케줄 task 모니터링](./126-common-scheduled-task-monitoring.md)

## 개요/목적
`ScheduledTaskExecutor.execute()`는 `claimed.taskType()`에 대응하는
`ScheduledTaskHandler` 빈이 없으면 warn 로그만 남기고 return한다. 이때
`ScheduledTaskExecutionStore.claim()`은 `lastAttemptedAt`만 갱신할 뿐 `status`를
`PENDING`에서 바꾸지 않으므로(`ScheduledTaskRepository.claim()` 쿼리 참고), 이 task는
`nextAttemptAt`도 그대로 남아 매 폴링 틱(`@Scheduled(fixedDelay=10000)`, #120)마다
`findDueTaskIds`에 다시 걸린다. 결과적으로 claim → warn 로그 → return이 무한 반복되며,
handler 등록 누락은 재시도로 해결되지 않는 배포 구성 오류인데도 시스템이 이를 "일시적
실패"처럼 영원히 재시도한다. 이번 수정은 handler 미등록도 기존 실패 경로(`recordFailure`)를
타게 하되, 재시도 자체가 무의미한 오류이므로 첫 시도에 곧장 `FAILED`로 격리한다. 새 HTTP
엔드포인트는 없다.

## 기존 로직 수정 — 변경 전/후 동작 차이

**변경 전** (`ScheduledTaskExecutor.execute()`):
```java
ScheduledTaskHandler handler = handlers.get(claimed.taskType());
if (handler == null) {
  log.warn("등록된 ScheduledTaskHandler가 없습니다(taskType={})", claimed.taskType());
  return; // status=PENDING, nextAttemptAt 그대로 → 다음 틱에 즉시 재claim
}
```

**변경 후**: handler 미등록도 `executionStore.recordFailure(...)`를 호출해 기존
`ScheduledTask.markFailed()` 경로(실패 카운트 증가 → `maxFailureCount` 도달 시 `FAILED`
격리)를 그대로 재사용한다. 다만 handler 미등록은 배포 구성 오류라 재시도로 해결되지 않으므로,
handler별 `retryPolicy()`를 조회할 handler 자체가 없는 것과 별개로 `RetryPolicy.DEFAULT`(5회
백오프)가 아닌 `maxFailureCount=1`짜리 전용 정책(`RetryPolicy.NON_RETRYABLE`)을 써서 첫 claim에
바로 `FAILED`로 격리한다.

```java
// RetryPolicy.java에 DEFAULT와 나란히 추가
public static final RetryPolicy NON_RETRYABLE =
    new RetryPolicy(1, Duration.ofSeconds(30), Duration.ofSeconds(30));
```

```java
ScheduledTaskHandler handler = handlers.get(claimed.taskType());
if (handler == null) {
  log.warn("등록된 ScheduledTaskHandler가 없습니다(taskType={})", claimed.taskType());
  executionStore.recordFailure(taskId, now,
      "등록된 ScheduledTaskHandler가 없습니다: taskType=" + claimed.taskType(),
      RetryPolicy.NON_RETRYABLE);
  return;
}
```

- `ScheduledTask.markFailed()`는 `failureCount >= maxFailureCount`가 되는 즉시
  `nextAttemptAt`을 갱신하지 않고 `status=FAILED`로 격리한다(`ScheduledTask.java:190-193`).
  `maxFailureCount=1`이므로 첫 실패에서 바로 이 분기를 타 별도 코드 경로 없이 즉시 격리가
  된다 — 로그 스팸과 불필요한 재claim이 다음 폴링 틱부터 바로 멈춘다.
- `FAILED`로 격리된 task는 #126에서 만든 관리자 모니터링 API(`GET
  /api/v1/admin/scheduled-tasks`)에 그대로 노출된다. `lastError`에 "등록된
  ScheduledTaskHandler가 없습니다: taskType=..." 문구가 그대로 남으므로, 별도 상태값이나
  API 스펙 변경 없이도 배포 담당자가 "일시적 실행 실패로 인한 FAILED"와 "설정 누락으로 인한
  FAILED"를 `lastError` 텍스트만으로 구분할 수 있다.
- `recordFailure`는 이미 `REQUIRES_NEW` 독립 트랜잭션이라(`ScheduledTaskExecutionStore`
  기존 구조) 이 변경으로 트랜잭션 경계가 새로 생기지 않는다.

## 영향받는 기존 코드/테스트
- `RetryPolicy.java`: `DEFAULT` 옆에 `NON_RETRYABLE`(`maxFailureCount=1`) 상수 추가
- `ScheduledTaskExecutor.execute()`: `handler == null` 분기 수정
- `ScheduledTaskExecutorTest`(또는 해당 테스트 클래스): handler 미등록 케이스에서
  - 첫 claim에서 곧장 `status=FAILED`로 전환되는지
  - `lastError`에 taskType을 포함한 안내 메시지가 남는지
  - 이후 폴링 틱(`findDueTaskIds`)에 더 이상 잡히지 않는지
  검증하는 테스트 추가

## 리스크 및 고려사항
- 이 변경은 순수하게 실패 격리 경로를 재사용하는 것이라 API 계약이나 DB 스키마 변경이
  없다 — [api-design.md](../../rules/api-design.md) 6원칙 검토 대상 아님.
- handler 미등록은 재시도로 해결되지 않는 배포 구성 오류이므로, taskType별 커스텀
  `retryPolicy()`를 조회할 handler 자체가 없다는 점과 별개로 `maxFailureCount=1`로 즉시
  격리하는 것이 backoff를 거치는 `RetryPolicy.DEFAULT`보다 합리적이다 — 원인이 배포
  설정 누락이라 30초든 31분이든 기다린다고 해결되지 않는다.
- 별도 상태값(enum) 신설은 고려하지 않는다. 지금 구분해야 할 "재시도 불가능한 설정
  오류" 종류가 handler 미등록 하나뿐이라, 기존 `lastError` 텍스트 필드만으로 모니터링
  구분이 충분하다 — enum·마이그레이션·`ScheduledTaskResponse`/#126 API 스펙 변경까지
  가는 것은 이번 이슈 범위를 넘어서는 과설계다.
