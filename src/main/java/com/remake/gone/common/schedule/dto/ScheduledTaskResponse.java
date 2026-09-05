package com.remake.gone.common.schedule.dto;

import com.remake.gone.common.schedule.entity.ScheduledTask;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import java.time.LocalDateTime;

/**
 * 관리자 모니터링용 스케줄 작업 응답 DTO(#126). {@link ScheduledTask} 컬럼을 그대로 노출하고
 * 별도 가공/재해석을 하지 않는다.
 *
 * @param id              작업 식별자
 * @param taskType        도메인을 구분하는 식별자(예: {@code "OUTING_TIMEOUT"})
 * @param referenceId     도메인 엔티티의 PK
 * @param scheduledAt     최초 실행 예정 시각
 * @param intervalSeconds 재실행 간격(초), {@code null}이면 1회성 작업
 * @param endAt           발송 상한 시각(cap), {@code null}이면 상한 없음
 * @param nextAttemptAt   다음 확인 예정 시각
 * @param lastExecutedAt  마지막으로 성공한 실행 시각
 * @param lastAttemptedAt 마지막 시도 시각(성공/실패 무관)
 * @param failureCount    연속 실패 횟수
 * @param lastError       마지막 실패 시 예외 메시지
 * @param status          작업 상태
 * @param createdAt       등록 시각
 */
public record ScheduledTaskResponse(
    Long id,
    String taskType,
    Long referenceId,
    LocalDateTime scheduledAt,
    Integer intervalSeconds,
    LocalDateTime endAt,
    LocalDateTime nextAttemptAt,
    LocalDateTime lastExecutedAt,
    LocalDateTime lastAttemptedAt,
    int failureCount,
    String lastError,
    ScheduledTaskStatus status,
    LocalDateTime createdAt
) {

  /**
   * {@link ScheduledTask} 엔티티를 응답 DTO로 변환합니다.
   *
   * @param task 변환 대상 작업 엔티티
   * @return 변환된 {@link ScheduledTaskResponse}
   */
  public static ScheduledTaskResponse from(ScheduledTask task) {
    return new ScheduledTaskResponse(
        task.getId(), task.getTaskType(), task.getReferenceId(), task.getScheduledAt(),
        task.getIntervalSeconds(), task.getEndAt(), task.getNextAttemptAt(),
        task.getLastExecutedAt(), task.getLastAttemptedAt(), task.getFailureCount(),
        task.getLastError(), task.getStatus(), task.getCreatedAt());
  }
}
