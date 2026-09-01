package com.remake.gone.common.schedule.service;

import com.remake.gone.common.schedule.entity.ScheduledTask;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.repository.ScheduledTaskRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScheduledTaskExecutor}가 task 상태를 읽고 쓰는 세 지점(claim/성공 기록/실패 기록)을
 * 서로 독립된 물리 트랜잭션으로 분리하기 위한 협력자(#99 코드 리뷰 보류 항목 (a) 대응).
 *
 * <p>이 세 메서드를 {@code ScheduledTaskExecutor}의 private 메서드로 두면 같은 빈 안에서의
 * self-invocation이라 {@code @Transactional}이 프록시를 거치지 않고 무시된다 — 별도 빈으로
 * 분리해야 각 메서드가 실제로 독립 트랜잭션을 연다. {@code ScheduledTaskExecutor.execute()}
 * 자신은 더 이상 {@code @Transactional}이 아니므로, {@code handler.handle()} 안에서 호출하는
 * 도메인 서비스의 {@code @Transactional(REQUIRED)} 메서드가 예외를 던져도 그 실패는 자신만의
 * 새 물리 트랜잭션을 롤백시킬 뿐 — {@link #claim}이 이미 커밋한 트랜잭션이나 이후
 * {@link #recordFailure}가 여는 트랜잭션과는 무관하다.
 */
@Component
@RequiredArgsConstructor
public class ScheduledTaskExecutionStore {

  private final ScheduledTaskRepository scheduledTaskRepository;

  /**
   * task를 원자적으로 claim하고, cap(end_at)을 이미 넘겼으면 그 자리에서 DONE 처리한다.
   *
   * @param taskId claim할 task의 ID
   * @param now    "지금" 시각
   * @return claim에 성공하고 아직 실행해야 하면 handler 호출에 필요한 정보의 스냅샷,
   *     claim 실패(이미 취소/변경됨)이거나 cap을 넘겨 더 실행할 필요가 없으면 {@code null}
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ClaimedTask claim(Long taskId, LocalDateTime now) {
    if (scheduledTaskRepository.claim(taskId, ScheduledTaskStatus.PENDING, now) == 0) {
      return null;
    }
    // claim()의 UPDATE가 방금 이 트랜잭션 안에서 갱신을 확정했으므로, 아래 조회는 항상
    // 해당 행을 찾는다(같은 트랜잭션 안이라 다른 트랜잭션이 그 사이에 끼어들 수 없다).
    ScheduledTask task = scheduledTaskRepository.findById(taskId).orElseThrow();
    if (task.isPastCap(now)) {
      task.markDone(now);
      return null;
    }
    return new ClaimedTask(task.getTaskType(), task.getReferenceId(), task.isOneShot());
  }

  /** handler가 예외 없이 반환했을 때 결과를 기록한다. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordSuccess(Long taskId, LocalDateTime now, boolean done) {
    scheduledTaskRepository.findById(taskId).ifPresent(task -> {
      if (done) {
        task.markDone(now);
      } else {
        task.markSucceeded(now);
      }
    });
  }

  /** handler가 예외를 던졌을 때 실패 이력을 기록한다. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(
      Long taskId, LocalDateTime now, String errorMessage, RetryPolicy retryPolicy) {
    scheduledTaskRepository.findById(taskId).ifPresent(task ->
        task.markFailed(now, errorMessage, retryPolicy.maxFailureCount(),
            retryPolicy.baseBackoff(), retryPolicy.maxBackoff()));
  }

  /** claim 시점의 task 스냅샷 — 트랜잭션이 닫힌 뒤에도 안전하게 쓸 수 있는 값만 담는다. */
  public record ClaimedTask(String taskType, Long referenceId, boolean oneShot) {
  }
}
