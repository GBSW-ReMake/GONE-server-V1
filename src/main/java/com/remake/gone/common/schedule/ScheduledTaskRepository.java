package com.remake.gone.common.schedule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
  @Query("select t.id from ScheduledTask t where t.status = :status and t.nextAttemptAt <= :now")
  List<Long> findDueTaskIds(
      @Param("status") ScheduledTaskStatus status, @Param("now") LocalDateTime now);

  /**
   * 관리자 모니터링 목록을 상태/task_type으로 필터링해 페이지네이션 조회합니다(#126).
   * {@code next_attempt_at} 오름차순(동률 시 {@code id} 오름차순)으로 정렬합니다.
   *
   * @param status   필터링할 상태, {@code null}이면 전체
   * @param taskType 필터링할 task_type, {@code null}이면 전체
   * @param pageable 페이지 요청(정렬은 쿼리에 고정돼 있어 무시됨)
   * @return 조건에 맞는 task 페이지
   */
  @Query("select t from ScheduledTask t "
      + "where (:status is null or t.status = :status) "
      + "and (:taskType is null or t.taskType = :taskType) "
      + "order by t.nextAttemptAt asc, t.id asc")
  Page<ScheduledTask> findWithFilters(
      @Param("status") ScheduledTaskStatus status,
      @Param("taskType") String taskType,
      Pageable pageable);

  /**
   * 특정 상태의 task 개수를 셉니다(#126, 모니터링 통계용).
   *
   * @param status 셀 상태
   * @return 해당 상태의 task 개수
   */
  long countByStatus(ScheduledTaskStatus status);
}
