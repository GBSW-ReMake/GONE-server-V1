package com.remake.gone.common.schedule.service;

import com.remake.gone.common.schedule.entity.ScheduledTask;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.repository.ScheduledTaskRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScheduledTask}의 등록/취소를 담당한다. {@code schedule}/{@code cancel}은 호출하는
 * 도메인 서비스의 트랜잭션에 참여한다(기본 전파 {@code REQUIRED}) — 예를 들어 outing의
 * {@code departOuting}/{@code returnOuting}이 이미 {@code @Transactional}이므로, 그 안에서
 * 호출하면 도메인 데이터와 {@code scheduled_task} 행이 같은 트랜잭션으로 원자적으로
 * 커밋/롤백된다.
 */
@Component
@RequiredArgsConstructor
public class ScheduledTaskService {

  private final ScheduledTaskRepository scheduledTaskRepository;

  /**
   * 같은 (taskType, referenceId)가 이미 PENDING이면 무시하고, DONE/FAILED로 끝난 이전 건이
   * 있으면 정리한 뒤 새로 등록한다. 유니크 제약(task_type, reference_id)이 있어, 한 번 끝난
   * (DONE) 건이 테이블에 남아있으면 같은 대상에 대한 이후 호출이 영원히 막히기 때문에 정리가
   * 필요하다.
   *
   * @param taskType    도메인을 구분하는 식별자(예: {@code "OUTING_TIMEOUT"})
   * @param referenceId 도메인 엔티티의 PK
   * @param scheduledAt 최초 실행 예정 시각
   * @param interval    성공 실행 후 재실행 간격, {@code null}이면 1회성 작업
   * @param cap         발송 상한(scheduledAt 기준 상대값), {@code null}이면 상한 없음
   */
  @Transactional
  public void schedule(String taskType, Long referenceId, LocalDateTime scheduledAt,
      Duration interval, Duration cap) {
    Optional<ScheduledTask> existing =
        scheduledTaskRepository.findByTaskTypeAndReferenceId(taskType, referenceId);
    if (existing.isPresent()) {
      // 이미 PENDING(대기/재시도 중)이면 새로 등록하지 않고 그대로 둔다 — 예를 들어
      // departOuting이 같은 outing에 대해 실수로 두 번 호출돼도 예약이 중복되지 않는다.
      if (existing.get().getStatus() == ScheduledTaskStatus.PENDING) {
        return;
      }
      // flush로 DELETE를 즉시 실행시킨다 — ScheduledTask는 GenerationType.IDENTITY라
      // 아래 save()가 즉시 INSERT를 실행하는데(IDENTITY는 생성된 PK를 바로 알아야 해서
      // Hibernate가 flush까지 미루지 못한다), flush 없이는 아직 DB에 남아있는 이 행과
      // 유니크 제약(task_type, reference_id)이 충돌해 save()가 실패한다.
      scheduledTaskRepository.delete(existing.get());
      scheduledTaskRepository.flush();
    }
    scheduledTaskRepository.save(
        new ScheduledTask(taskType, referenceId, scheduledAt, interval, cap));
  }

  /**
   * 특정 대상에 대한 예약을 취소한다. 등록된 예약이 없어도 조용히 끝난다.
   *
   * @param taskType    도메인을 구분하는 식별자
   * @param referenceId 도메인 엔티티의 PK
   */
  @Transactional
  public void cancel(String taskType, Long referenceId) {
    scheduledTaskRepository.deleteByTaskTypeAndReferenceId(taskType, referenceId);
  }
}
