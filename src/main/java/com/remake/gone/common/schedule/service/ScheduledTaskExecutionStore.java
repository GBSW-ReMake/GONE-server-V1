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
 * {@link ScheduledTaskExecutor}가 task 상태를 읽고 쓰는 지점을 독립된 물리 트랜잭션으로
 * 나누기 위한 협력자. claim(#99 코드 리뷰 보류 항목 (a) 대응)과 실패 기록은 handler 실행과
 * 분리된 자신만의 REQUIRES_NEW 트랜잭션을 갖지만, handler 호출과 성공 기록({@link
 * #executeAndRecordSuccess})은 하나의 트랜잭션으로 묶여 있다 — 그 이유는 해당 메서드 문서
 * 참고(#99 CodeRabbit 지적 B 대응).
 *
 * <p>이 메서드들을 {@code ScheduledTaskExecutor}의 private 메서드로 두면 같은 빈 안에서의
 * self-invocation이라 {@code @Transactional}이 프록시를 거치지 않고 무시된다 — 별도 빈으로
 * 분리해야 각 메서드가 실제로 독립 트랜잭션을 연다. {@code ScheduledTaskExecutor.execute()}
 * 자신은 더 이상 {@code @Transactional}이 아니므로, {@code handler.handle()} 안에서 호출하는
 * 도메인 서비스의 {@code @Transactional(REQUIRED)} 메서드가 예외를 던져도 그 실패는 자신만의
 * 물리 트랜잭션을 롤백시킬 뿐 — {@link #claim}이 이미 커밋한 트랜잭션이나 이후
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

  /**
   * handler를 호출하고 성공 결과를 같은 물리 트랜잭션 안에서 기록한다(#99 CodeRabbit 지적
   * B 대응). {@code handler.handle()}이 호출하는 도메인 서비스(예: {@code
   * OutingService.checkAndNotifyTimeout})는 기본 전파(REQUIRED)라 이 트랜잭션에 참여할 뿐
   * 독립적으로 커밋하지 않는다 — 그 안에서 일어난 부수 효과(알림 저장 등)는 이 메서드가
   * 끝까지 정상 반환해 커밋될 때만 함께 확정되고, 어디서든 예외가 나면 부수 효과까지 통째로
   * 롤백된다. 이전에는 handler 호출과 결과 기록이 서로 다른 REQUIRES_NEW 트랜잭션이라, 부수
   * 효과는 이미 커밋됐는데 기록만 독립적으로 실패해 다음 폴링에 handler가 다시 불려 알림이
   * 중복 발송될 수 있었다 — 하나의 트랜잭션으로 묶으면 그 중간 상태 자체가 생기지 않는다.
   *
   * <p><b>전제</b>: 이 보장은 handler의 부수 효과가 이 물리 트랜잭션 안에서 끝나는 DB 쓰기일
   * 때만 유효하다. 알림 도메인에 FCM 푸시(외부 API 호출)가 추가되면 그 호출은 트랜잭션
   * 롤백으로 되돌릴 수 없으므로, 그 시점에는 이 방식만으로 부족해진다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void executeAndRecordSuccess(
      ScheduledTaskHandler handler, ClaimedTask claimed, Long taskId, LocalDateTime now) {
    boolean done = handler.handle(claimed.referenceId());
    scheduledTaskRepository.findById(taskId).ifPresent(task -> {
      if (done || claimed.oneShot()) {
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
