package com.remake.gone.common.schedule.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.schedule.dto.ScheduledTaskResponse;
import com.remake.gone.common.schedule.dto.ScheduledTaskStatsResponse;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.service.ScheduledTaskAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code scheduled_task}(#120) 관리자 모니터링/재시도/삭제 API 컨트롤러(#126). JobRunr
 * 대시보드가 제공하는 job 관리 기능(조회+통계+Requeue+Delete)과 범위를 맞췄다.
 */
@RestController
@RequestMapping("/api/v1/scheduled-tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

  private final ScheduledTaskAdminService scheduledTaskAdminService;

  /**
   * 스케줄 작업 목록을 상태/task_type으로 필터링해 페이지네이션 조회합니다.
   *
   * @param status   필터링할 상태(선택)
   * @param taskType 필터링할 task_type(선택)
   * @param page     페이지 번호(기본값 0)
   * @param size     페이지 크기(기본값 20, 1~100)
   * @return 페이지네이션된 작업 목록
   */
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PageResponse<ScheduledTaskResponse>> getTasks(
      @RequestParam(required = false) ScheduledTaskStatus status,
      @RequestParam(required = false) String taskType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(
        scheduledTaskAdminService.getTasks(status, taskType, page, size),
        "스케줄 작업 목록을 조회했습니다.");
  }

  /**
   * 상태별 작업 개수 요약을 조회합니다.
   *
   * @return 상태별 개수와 전체 개수
   */
  @GetMapping("/stats")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ScheduledTaskStatsResponse> getStats() {
    return ApiResponse.success(
        scheduledTaskAdminService.getStats(), "스케줄 작업 통계를 조회했습니다.");
  }

  /**
   * FAILED로 격리된 작업을 다시 PENDING으로 되돌려 즉시 재시도 대상이 되게 합니다.
   *
   * @param id 재시도시킬 작업의 PK
   * @return 재시도 반영 후 최신 상태
   */
  @PostMapping("/{id}/retry")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ScheduledTaskResponse> retry(@PathVariable Long id) {
    return ApiResponse.success(
        scheduledTaskAdminService.retry(id), "스케줄 작업을 재시도 대상으로 등록했습니다.");
  }

  /**
   * 작업을 상태와 무관하게 삭제합니다.
   *
   * @param id 삭제할 작업의 PK
   * @return 데이터 없는 성공 응답
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    scheduledTaskAdminService.delete(id);
    return ApiResponse.success(null, "스케줄 작업을 삭제했습니다.");
  }
}
