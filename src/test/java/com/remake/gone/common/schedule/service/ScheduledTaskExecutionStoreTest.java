package com.remake.gone.common.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.schedule.entity.ScheduledTask;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.repository.ScheduledTaskRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ScheduledTaskExecutionStore}에 대한 단위 테스트. 실제 {@code @Transactional} 전파
 * 검증은 {@code ScheduledTaskExecutorIntegrationTest}가 담당하고, 여기서는 claim/기록 각
 * 메서드의 분기 로직만 mock 리포지토리로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskExecutionStoreTest {

  private static final String TASK_TYPE = "OUTING_TIMEOUT";
  private static final Long TASK_ID = 1L;
  private static final Long REFERENCE_ID = 10L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 15, 0);

  @Mock
  private ScheduledTaskRepository scheduledTaskRepository;

  @InjectMocks
  private ScheduledTaskExecutionStore executionStore;

  private ScheduledTask task(Duration interval, Duration cap) {
    return new ScheduledTask(TASK_TYPE, REFERENCE_ID, NOW, interval, cap);
  }

  @Nested
  @DisplayName("claim")
  class Claim {

    @Test
    @DisplayName("claim에 성공하고 cap 이전이면 handler 호출에 필요한 스냅샷을 반환한다")
    void returnsSnapshotWhenClaimedAndBeforeCap() {
      ScheduledTask task = task(Duration.ofMinutes(1), Duration.ofHours(1));
      given(scheduledTaskRepository.claim(eq(TASK_ID), eq(ScheduledTaskStatus.PENDING), eq(NOW)))
          .willReturn(1);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      ScheduledTaskExecutionStore.ClaimedTask result = executionStore.claim(TASK_ID, NOW);

      assertThat(result).isEqualTo(
          new ScheduledTaskExecutionStore.ClaimedTask(TASK_TYPE, REFERENCE_ID, false));
    }

    @Test
    @DisplayName("claim이 갱신한 행이 0건이면 조회 없이 null을 반환한다")
    void returnsNullWhenClaimAffectsNoRows() {
      given(scheduledTaskRepository.claim(eq(TASK_ID), eq(ScheduledTaskStatus.PENDING), eq(NOW)))
          .willReturn(0);

      ScheduledTaskExecutionStore.ClaimedTask result = executionStore.claim(TASK_ID, NOW);

      assertThat(result).isNull();
      verify(scheduledTaskRepository, never()).findById(any());
    }

    @Test
    @DisplayName("claim에 성공했지만 cap을 이미 넘겼으면 DONE 처리하고 null을 반환한다")
    void marksDoneAndReturnsNullWhenPastCap() {
      ScheduledTask task = task(Duration.ofMinutes(1), Duration.ofMinutes(30));
      LocalDateTime afterCap = NOW.plusHours(1);
      given(scheduledTaskRepository.claim(
          eq(TASK_ID), eq(ScheduledTaskStatus.PENDING), eq(afterCap))).willReturn(1);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      ScheduledTaskExecutionStore.ClaimedTask result = executionStore.claim(TASK_ID, afterCap);

      assertThat(result).isNull();
      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.DONE);
    }
  }

  @Nested
  @DisplayName("recordSuccess")
  class RecordSuccess {

    @Test
    @DisplayName("done=true면 DONE 처리한다")
    void marksDoneWhenDone() {
      ScheduledTask task = task(Duration.ofMinutes(1), null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      executionStore.recordSuccess(TASK_ID, NOW, true);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.DONE);
    }

    @Test
    @DisplayName("done=false면 다음 실행을 예약한다")
    void reschedulesWhenNotDone() {
      ScheduledTask task = task(Duration.ofMinutes(1), null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      executionStore.recordSuccess(TASK_ID, NOW, false);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
      assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    @DisplayName("그 사이 task가 사라졌으면 조용히 아무것도 하지 않는다")
    void doesNothingWhenTaskGone() {
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.empty());

      executionStore.recordSuccess(TASK_ID, NOW, true);
      // 예외 없이 끝나면 충분하다 — 별도 assertion 대상이 없다.
    }
  }

  @Nested
  @DisplayName("recordFailure")
  class RecordFailure {

    @Test
    @DisplayName("실패 이력을 기록하고 재시도 정책에 따라 다음 시도를 예약한다")
    void recordsFailureAndSchedulesRetry() {
      ScheduledTask task = task(Duration.ofMinutes(1), null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      executionStore.recordFailure(TASK_ID, NOW, "boom", RetryPolicy.DEFAULT);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
      assertThat(task.getFailureCount()).isEqualTo(1);
      assertThat(task.getLastError()).isEqualTo("boom");
    }
  }
}
