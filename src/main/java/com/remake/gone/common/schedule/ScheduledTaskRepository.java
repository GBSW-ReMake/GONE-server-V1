package com.remake.gone.common.schedule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@link ScheduledTask}에 대한 리포지토리. */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

  /**
   * 특정 대상에 대해 이미 등록된 예약을 조회합니다.
   *
   * @param taskType    도메인을 구분하는 식별자
   * @param referenceId 도메인 엔티티의 PK
   * @return 예약 정보, 없으면 {@link Optional#empty()}
   */
  Optional<ScheduledTask> findByTaskTypeAndReferenceId(String taskType, Long referenceId);

  /**
   * 특정 대상에 대한 예약을 삭제합니다.
   *
   * @param taskType    도메인을 구분하는 식별자
   * @param referenceId 도메인 엔티티의 PK
   */
  void deleteByTaskTypeAndReferenceId(String taskType, Long referenceId);

  /**
   * 지금 실행해야 할 task의 ID만 조회한다. 실제 실행은 {@link ScheduledTaskExecutor}가 건별
   * 독립 트랜잭션에서 다시 조회해 처리한다 — 조회 시점과 실행 시점 사이에 취소({@code cancel})나
   * 다른 실행이 끼어들 수 있으므로, 오래된 스냅샷을 그대로 쓰지 않고 실행 직전에 항상 최신
   * 상태를 다시 읽는다.
   */
  @Query("select t.id from ScheduledTask t where t.status = 'PENDING' and t.nextAttemptAt <= :now")
  List<Long> findDueTaskIds(@Param("now") LocalDateTime now);
}
