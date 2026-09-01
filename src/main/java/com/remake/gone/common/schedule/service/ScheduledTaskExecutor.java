package com.remake.gone.common.schedule.service;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code ScheduledTask} 하나를 claim → 실행 → 결과 기록의 세 단계로 나눠 처리한다. 트리거
 * ({@link ScheduledTaskRunner})와 실제 실행을 분리해, 한 폴링 틱에서 처리하는 여러 건 중
 * 한 건의 실패가 다른 건에 영향을 주지 않게 한다.
 *
 * <p>이 클래스 자신은 {@code @Transactional}이 아니다(#99 코드 리뷰 보류 항목 (a) 대응) — 세
 * 단계 각각의 트랜잭션 경계는 {@link ScheduledTaskExecutionStore}(각 메서드가
 * {@code REQUIRES_NEW})가 담당한다. {@code execute()}가 통째로 하나의 트랜잭션이었을 때는
 * {@code handler.handle()} 안에서 호출하는 도메인 서비스의 {@code @Transactional(REQUIRED)}
 * 메서드가 예외를 던지면 그 실패가 이 트랜잭션 전체를 rollback-only로 만들어, catch 블록의
 * 실패 기록(markFailed)까지 커밋 시점에 {@code UnexpectedRollbackException}으로 함께
 * 유실됐다 — 세 단계를 별도 물리 트랜잭션으로 쪼개면 handler 내부의 실패가 자신만의
 * 트랜잭션을 롤백시킬 뿐, claim이 이미 커밋한 상태나 이후 결과 기록 트랜잭션과는 무관해진다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledTaskExecutor {

  private final ScheduledTaskExecutionStore executionStore;
  private final Map<String, ScheduledTaskHandler> handlers; // 빈 이름 = task_type

  /**
   * ID로 task를 claim해 실행한다.
   *
   * @param taskId 실행할 task의 ID
   * @param now    실행 시각(호출자와 동일한 값을 써서 한 틱 안에서 시각 기준을 통일한다)
   */
  public void execute(Long taskId, LocalDateTime now) {
    ScheduledTaskExecutionStore.ClaimedTask claimed = executionStore.claim(taskId, now);
    if (claimed == null) {
      // claim 실패(이미 취소/처리됨) 또는 cap을 넘겨 종료 처리됨 — 둘 다 더 할 일이 없다.
      return;
    }
    ScheduledTaskHandler handler = handlers.get(claimed.taskType());
    if (handler == null) {
      // 매핑이 없다는 건 배포 실수(핸들러 등록을 빠뜨림)일 가능성이 높다 — 다음 폴링
      // 틱에서 다시 같은 경고가 반복되므로 로그로 바로 드러난다.
      log.warn("등록된 ScheduledTaskHandler가 없습니다(taskType={})", claimed.taskType());
      return;
    }
    try {
      boolean done = handler.handle(claimed.referenceId());
      // 둘 중 하나라도 참이면 더 이상 재실행하지 않는다: (1) 핸들러가 스스로 "끝났다"고
      // 판단, (2) 애초에 1회성 작업이라 재실행 개념이 없음.
      executionStore.recordSuccess(taskId, now, done || claimed.oneShot());
    } catch (Exception e) {
      log.error("ScheduledTask 실행 실패(id={}, taskType={}, referenceId={})",
          taskId, claimed.taskType(), claimed.referenceId(), e);
      executionStore.recordFailure(taskId, now, e.getMessage(), handler.retryPolicy());
    }
  }
}
