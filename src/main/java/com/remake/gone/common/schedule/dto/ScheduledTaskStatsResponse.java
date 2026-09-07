package com.remake.gone.common.schedule.dto;

/**
 * 스케줄 작업 상태별 개수 요약 응답 DTO(#126).
 *
 * @param pending PENDING 상태 개수
 * @param done    DONE 상태 개수
 * @param failed  FAILED 상태 개수
 * @param total   전체 개수(pending + done + failed)
 */
public record ScheduledTaskStatsResponse(long pending, long done, long failed, long total) {}
