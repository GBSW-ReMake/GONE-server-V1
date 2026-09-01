package com.remake.gone.common.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.schedule.ScheduledTask;
import com.remake.gone.common.schedule.ScheduledTaskRepository;
import com.remake.gone.common.schedule.ScheduledTaskStatus;
import com.remake.gone.common.schedule.dto.ScheduledTaskStatsResponse;
import com.remake.gone.common.schedule.exception.ScheduleErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * {@link ScheduledTaskAdminService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskAdminServiceTest {

  private static final Long TASK_ID = 1L;
  private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 9, 1, 15, 0);

  @Mock
  private ScheduledTaskRepository scheduledTaskRepository;

  @InjectMocks
  private ScheduledTaskAdminService scheduledTaskAdminService;

  @Nested
  @DisplayName("getTasks")
  class GetTasks {

    @Test
    @DisplayName("page가 음수면 INVALID_PAGE 예외를 던진다")
    void rejectsNegativePage() {
      assertThatThrownBy(() -> scheduledTaskAdminService.getTasks(null, null, -1, 20))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ScheduleErrorCode.INVALID_PAGE);
    }

    @Test
    @DisplayName("size가 0이면 INVALID_PAGE 예외를 던진다")
    void rejectsZeroSize() {
      assertThatThrownBy(() -> scheduledTaskAdminService.getTasks(null, null, 0, 0))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ScheduleErrorCode.INVALID_PAGE);
    }

    @Test
    @DisplayName("size가 100을 넘으면 INVALID_PAGE 예외를 던진다")
    void rejectsSizeOverMax() {
      assertThatThrownBy(() -> scheduledTaskAdminService.getTasks(null, null, 0, 101))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ScheduleErrorCode.INVALID_PAGE);
    }

    @Test
    @DisplayName("정상 파라미터면 리포지토리 조회 결과를 그대로 페이지 응답으로 감싼다")
    void delegatesToRepositoryAndWrapsPageResponse() {
      ScheduledTask task =
          new ScheduledTask("OUTING_TIMEOUT", 10L, SCHEDULED_AT, null, null);
      Page<ScheduledTask> page = new PageImpl<>(List.of(task), PageRequest.of(0, 20), 1);
      given(scheduledTaskRepository.findWithFilters(
          eq(ScheduledTaskStatus.PENDING), eq("OUTING_TIMEOUT"), any(PageRequest.class)))
          .willReturn(page);

      PageResponse<?> result = scheduledTaskAdminService.getTasks(
          ScheduledTaskStatus.PENDING, "OUTING_TIMEOUT", 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.totalElements()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("getStats")
  class GetStats {

    @Test
    @DisplayName("상태별 개수와 전체 개수를 반환한다")
    void returnsCountsPerStatus() {
      given(scheduledTaskRepository.countByStatus(ScheduledTaskStatus.PENDING)).willReturn(3L);
      given(scheduledTaskRepository.countByStatus(ScheduledTaskStatus.DONE)).willReturn(128L);
      given(scheduledTaskRepository.countByStatus(ScheduledTaskStatus.FAILED)).willReturn(2L);

      ScheduledTaskStatsResponse result = scheduledTaskAdminService.getStats();

      assertThat(result).isEqualTo(new ScheduledTaskStatsResponse(3, 128, 2, 133));
    }
  }

  @Nested
  @DisplayName("retry")
  class Retry {

    @Test
    @DisplayName("FAILED 작업을 PENDING으로 되돌린다")
    void resetsFailedTaskToPending() {
      ScheduledTask task =
          new ScheduledTask("OUTING_TIMEOUT", 10L, SCHEDULED_AT, Duration.ofMinutes(1), null);
      // maxFailureCount(5)번째 실패에야 FAILED로 바뀐다 — ScheduledTaskExecutorTest의
      // "5회 연속 실패하면 FAILED로 격리한다"와 동일한 전제.
      for (int i = 0; i < 5; i++) {
        task.markFailed(SCHEDULED_AT, "boom", 5, Duration.ofSeconds(30), Duration.ofMinutes(30));
      }
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      scheduledTaskAdminService.retry(TASK_ID);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
      assertThat(task.getFailureCount()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 id면 TASK_NOT_FOUND 예외를 던진다")
    void rejectsMissingTask() {
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() -> scheduledTaskAdminService.retry(TASK_ID))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ScheduleErrorCode.TASK_NOT_FOUND);
    }

    @Test
    @DisplayName("FAILED가 아닌 작업이면 NOT_FAILED 예외를 던진다")
    void rejectsNonFailedTask() {
      ScheduledTask task =
          new ScheduledTask("OUTING_TIMEOUT", 10L, SCHEDULED_AT, null, null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      assertThatThrownBy(() -> scheduledTaskAdminService.retry(TASK_ID))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ScheduleErrorCode.NOT_FAILED);
    }
  }

  @Nested
  @DisplayName("delete")
  class Delete {

    @Test
    @DisplayName("존재하면 상태와 무관하게 삭제한다")
    void deletesRegardlessOfStatus() {
      scheduledTaskAdminService.delete(TASK_ID);

      verify(scheduledTaskRepository).deleteById(TASK_ID);
    }

    @Test
    @DisplayName("존재하지 않는 id면(동시 삭제 레이스 포함) TASK_NOT_FOUND 예외를 던진다")
    void rejectsMissingTask() {
      doThrow(new EmptyResultDataAccessException(1))
          .when(scheduledTaskRepository).deleteById(TASK_ID);

      assertThatThrownBy(() -> scheduledTaskAdminService.delete(TASK_ID))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ScheduleErrorCode.TASK_NOT_FOUND);
    }
  }
}
