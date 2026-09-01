package com.remake.gone.common.schedule.service;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.schedule.ScheduledTask;
import com.remake.gone.common.schedule.ScheduledTaskRepository;
import com.remake.gone.common.schedule.ScheduledTaskStatus;
import com.remake.gone.common.schedule.dto.ScheduledTaskResponse;
import com.remake.gone.common.schedule.dto.ScheduledTaskStatsResponse;
import com.remake.gone.common.schedule.exception.ScheduleErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code scheduled_task}(#120)에 대한 관리자 전용 조회/재시도/삭제를 담당한다(#126).
 * {@code ScheduledTaskService}(#120, 도메인 코드가 스케줄을 등록/취소할 때 쓰는 내부 API)와는
 * 소비자와 책임이 달라 별도 서비스로 분리했다.
 */
@Service
@RequiredArgsConstructor
public class ScheduledTaskAdminService {

  private static final int MAX_PAGE_SIZE = 100;
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final ScheduledTaskRepository scheduledTaskRepository;

  /**
   * 상태/task_type으로 필터링한 스케줄 작업 목록을 페이지네이션 조회합니다.
   *
   * @param status   필터링할 상태, {@code null}이면 전체
   * @param taskType 필터링할 task_type, {@code null}이면 전체
   * @param page     페이지 번호(0부터 시작)
   * @param size     페이지 크기(1~100)
   * @return 페이지네이션된 작업 목록
   */
  public PageResponse<ScheduledTaskResponse> getTasks(
      ScheduledTaskStatus status, String taskType, int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw new CustomException(ScheduleErrorCode.INVALID_PAGE);
    }
    Page<ScheduledTask> taskPage = scheduledTaskRepository.findWithFilters(
        status, taskType, PageRequest.of(page, size));
    return PageResponse.of(taskPage.map(ScheduledTaskResponse::from));
  }

  /**
   * 상태별 작업 개수 요약을 조회합니다.
   *
   * @return 상태별 개수와 전체 개수
   */
  public ScheduledTaskStatsResponse getStats() {
    long pending = scheduledTaskRepository.countByStatus(ScheduledTaskStatus.PENDING);
    long done = scheduledTaskRepository.countByStatus(ScheduledTaskStatus.DONE);
    long failed = scheduledTaskRepository.countByStatus(ScheduledTaskStatus.FAILED);
    return new ScheduledTaskStatsResponse(pending, done, failed, pending + done + failed);
  }

  /**
   * FAILED로 격리된 작업을 PENDING으로 되돌려 즉시 재시도 대상이 되게 합니다. 다음 폴링
   * 틱(최대 10초 뒤)에 {@code ScheduledTaskRunner}가 집어가 실제로 재실행합니다 — 이
   * 메서드 자체는 handler를 호출하지 않습니다.
   *
   * @param id 재시도시킬 작업의 PK
   * @return 재시도 반영 후 최신 상태
   */
  @Transactional
  public ScheduledTaskResponse retry(Long id) {
    ScheduledTask task = scheduledTaskRepository.findById(id)
        .orElseThrow(() -> new CustomException(ScheduleErrorCode.TASK_NOT_FOUND));
    if (task.getStatus() != ScheduledTaskStatus.FAILED) {
      throw new CustomException(ScheduleErrorCode.NOT_FAILED);
    }
    task.retry(LocalDateTime.now(KST));
    return ScheduledTaskResponse.from(task);
  }

  /**
   * 작업을 상태와 무관하게 삭제합니다.
   *
   * @param id 삭제할 작업의 PK
   */
  @Transactional
  public void delete(Long id) {
    if (!scheduledTaskRepository.existsById(id)) {
      throw new CustomException(ScheduleErrorCode.TASK_NOT_FOUND);
    }
    scheduledTaskRepository.deleteById(id);
  }
}
