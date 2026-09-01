package com.remake.gone.common.schedule;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScheduledTask} 하나를 건별 독립 트랜잭션에서 실행하고, 결과에 따라 상태를 갱신한다.
 * 트리거({@link ScheduledTaskRunner})와 실제 실행을 분리해, 한 폴링 틱에서 처리하는 여러 건
 * 중 한 건의 실패가 다른 건에 영향을 주지 않게 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledTaskExecutor {

  private final ScheduledTaskRepository scheduledTaskRepository;
  private final Map<String, ScheduledTaskHandler> handlers; // 빈 이름 = task_type

  /**
   * ID로 task를 다시 조회해 실행한다. {@code REQUIRES_NEW}로 매번 새 트랜잭션을 여는 이유:
   * {@link ScheduledTaskRunner#run()}의 반복문 안에서 호출되지만, 이 task의 성공/실패가 같은
   * 틱에서 처리 중인 다른 task의 트랜잭션과 완전히 분리돼야 하기 때문이다 — 한 건의 실패가
   * 트랜잭션 전체를 롤백시켜 이미 처리된 다른 건까지 되돌리는 일이 없게 한다.
   *
   * @param taskId 실행할 task의 ID
   * @param now    실행 시각(호출자와 동일한 값을 써서 한 틱 안에서 시각 기준을 통일한다)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void execute(Long taskId, LocalDateTime now) {
    // Runner가 findDueTaskIds로 읽은 시점과 지금 사이에 다른 요청(예: returnOuting →
    // cancel)이 이 행을 지웠거나 이미 처리해 상태가 바뀌었을 수 있어, 그 경우 아무것도 하지
    // 않고 조용히 끝낸다.
    ScheduledTask task = scheduledTaskRepository.findById(taskId).orElse(null);
    if (task == null || task.getStatus() != ScheduledTaskStatus.PENDING) {
      return;
    }
    ScheduledTaskHandler handler = handlers.get(task.getTaskType());
    if (handler == null) {
      // 매핑이 없다는 건 배포 실수(핸들러 등록을 빠뜨림)일 가능성이 높다 — 다음 폴링
      // 틱에서 다시 같은 경고가 반복되므로 로그로 바로 드러난다.
      log.warn("등록된 ScheduledTaskHandler가 없습니다(taskType={})", task.getTaskType());
      return;
    }
    try {
      boolean done = handler.handle(task.getReferenceId());
      // 셋 중 하나라도 참이면 더 이상 재실행하지 않는다: (1) 핸들러가 스스로 "끝났다"고
      // 판단, (2) end_at(cap)을 넘겨 더 기다려도 의미가 없음, (3) 애초에 1회성 작업이라
      // 재실행 개념이 없음.
      if (done || task.isPastCap(now) || task.isOneShot()) {
        task.markDone();
      } else {
        task.markSucceeded(now);
      }
    } catch (Exception e) {
      RetryPolicy retryPolicy = handler.retryPolicy();
      // 핸들러 내부 예외(알림 저장 실패, DB 순간 장애 등)를 여기서 잡아 트랜잭션을 정상
      // 커밋시킨다 — markFailed가 기록한 실패 카운트/다음 시도 시각까지 함께 저장돼야 다음
      // 폴링 틱이 그 값을 보고 재시도 여부를 판단할 수 있다(예외를 그대로 던지면 이
      // 트랜잭션 자체가 롤백되어 실패 기록조차 남지 않는다).
      task.markFailed(now, e.getMessage(), retryPolicy.maxFailureCount(),
          retryPolicy.baseBackoff(), retryPolicy.maxBackoff());
      log.error("ScheduledTask 실행 실패(id={}, taskType={}, referenceId={}, failureCount={})",
          task.getId(), task.getTaskType(), task.getReferenceId(), task.getFailureCount(), e);
    }
  }
}
