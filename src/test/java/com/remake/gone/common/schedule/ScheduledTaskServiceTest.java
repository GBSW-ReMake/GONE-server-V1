package com.remake.gone.common.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ScheduledTaskService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskServiceTest {

  private static final String TASK_TYPE = "OUTING_TIMEOUT";
  private static final Long REFERENCE_ID = 1L;
  private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 9, 1, 15, 0);

  @Mock
  private ScheduledTaskRepository scheduledTaskRepository;

  @InjectMocks
  private ScheduledTaskService scheduledTaskService;

  @Nested
  @DisplayName("schedule")
  class Schedule {

    @Test
    @DisplayName("기존 등록이 없으면 새로 저장한다")
    void savesNewTaskWhenNoneExists() {
      given(scheduledTaskRepository.findByTaskTypeAndReferenceId(TASK_TYPE, REFERENCE_ID))
          .willReturn(Optional.empty());

      scheduledTaskService.schedule(
          TASK_TYPE, REFERENCE_ID, SCHEDULED_AT, Duration.ofMinutes(1), Duration.ofHours(3));

      ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
      verify(scheduledTaskRepository).save(captor.capture());
      assertThat(captor.getValue().getTaskType()).isEqualTo(TASK_TYPE);
      assertThat(captor.getValue().getReferenceId()).isEqualTo(REFERENCE_ID);
    }

    @Test
    @DisplayName("이미 PENDING이면 중복 등록하지 않고 그대로 둔다")
    void ignoresDuplicateWhenPending() {
      ScheduledTask pending =
          new ScheduledTask(TASK_TYPE, REFERENCE_ID, SCHEDULED_AT, null, null);
      given(scheduledTaskRepository.findByTaskTypeAndReferenceId(TASK_TYPE, REFERENCE_ID))
          .willReturn(Optional.of(pending));

      scheduledTaskService.schedule(TASK_TYPE, REFERENCE_ID, SCHEDULED_AT, null, null);

      verify(scheduledTaskRepository, never()).delete(any());
      verify(scheduledTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName("DONE/FAILED로 끝난 이전 건은 정리한 뒤 재등록한다")
    void cleansUpFinishedTaskBeforeReRegistering() {
      ScheduledTask done = new ScheduledTask(TASK_TYPE, REFERENCE_ID, SCHEDULED_AT, null, null);
      done.markDone();
      given(scheduledTaskRepository.findByTaskTypeAndReferenceId(TASK_TYPE, REFERENCE_ID))
          .willReturn(Optional.of(done));

      scheduledTaskService.schedule(TASK_TYPE, REFERENCE_ID, SCHEDULED_AT, null, null);

      verify(scheduledTaskRepository).delete(done);
      verify(scheduledTaskRepository).save(any(ScheduledTask.class));
    }
  }

  @Nested
  @DisplayName("cancel")
  class Cancel {

    @Test
    @DisplayName("해당 taskType/referenceId의 예약을 삭제한다")
    void deletesByTaskTypeAndReferenceId() {
      scheduledTaskService.cancel(TASK_TYPE, REFERENCE_ID);

      verify(scheduledTaskRepository).deleteByTaskTypeAndReferenceId(TASK_TYPE, REFERENCE_ID);
    }
  }
}
