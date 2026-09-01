package com.remake.gone.common.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ScheduledTaskRunner}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskRunnerTest {

  @Mock
  private ScheduledTaskRepository scheduledTaskRepository;

  @Mock
  private ScheduledTaskExecutor scheduledTaskExecutor;

  @InjectMocks
  private ScheduledTaskRunner scheduledTaskRunner;

  @Test
  @DisplayName("findDueTaskIds가 반환한 ID마다 executor.execute를 호출한다")
  void executesEachDueTaskId() {
    given(scheduledTaskRepository.findDueTaskIds(any(LocalDateTime.class)))
        .willReturn(List.of(1L, 2L, 3L));

    scheduledTaskRunner.run();

    verify(scheduledTaskExecutor).execute(eq(1L), any(LocalDateTime.class));
    verify(scheduledTaskExecutor).execute(eq(2L), any(LocalDateTime.class));
    verify(scheduledTaskExecutor).execute(eq(3L), any(LocalDateTime.class));
  }
}
