package com.remake.gone.common.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ScheduledTaskExecutor}에 대한 단위 테스트. {@code handlers}는 빈 이름으로 매핑되는
 * {@code Map<String, ScheduledTaskHandler>}라 {@code @InjectMocks} 대신 직접 조립한다.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskExecutorTest {

  private static final String TASK_TYPE = "OUTING_TIMEOUT";
  private static final Long TASK_ID = 1L;
  private static final Long REFERENCE_ID = 10L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 15, 0);

  @Mock
  private ScheduledTaskRepository scheduledTaskRepository;

  private ScheduledTaskHandler handler;
  private ScheduledTaskExecutor executor;

  @BeforeEach
  void setUp() {
    // CALLS_REAL_METHODS: 순수 mock()은 인터페이스의 default 메서드(retryPolicy())도
    // 스텁 없이는 null을 반환한다 — 재정의하지 않은 핸들러가 실제로 RetryPolicy.DEFAULT를
    // 반환하는 프로덕션 동작을 재현하려면 default 메서드가 실제로 호출돼야 한다.
    handler = mock(ScheduledTaskHandler.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    executor = new ScheduledTaskExecutor(scheduledTaskRepository, Map.of(TASK_TYPE, handler));
  }

  private ScheduledTask task(Duration interval, Duration cap) {
    return new ScheduledTask(TASK_TYPE, REFERENCE_ID, NOW, interval, cap);
  }

  @Nested
  @DisplayName("handler.handle 반환값에 따른 처리")
  class HandleResult {

    @Test
    @DisplayName("true를 반환하면 DONE 처리하고 시도/실행 시각을 남긴다")
    void marksDoneWhenHandlerReturnsTrue() {
      ScheduledTask task = task(Duration.ofMinutes(1), null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willReturn(true);

      executor.execute(TASK_ID, NOW);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.DONE);
      assertThat(task.getLastAttemptedAt()).isEqualTo(NOW);
      assertThat(task.getLastExecutedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("false를 반환하고 interval이 있고 cap 이전이면 다음 실행을 예약한다")
    void reschedulesWhenNotDoneAndBeforeCap() {
      ScheduledTask task = task(Duration.ofMinutes(1), Duration.ofHours(1));
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willReturn(false);

      executor.execute(TASK_ID, NOW);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
      assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    @DisplayName("1회성 작업(interval 없음)은 false를 반환해도 DONE 처리한다")
    void marksDoneForOneShotEvenWhenFalse() {
      ScheduledTask task = task(null, null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willReturn(false);

      executor.execute(TASK_ID, NOW);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.DONE);
    }

    @Test
    @DisplayName("cap(end_at)을 넘겼으면 반환값과 무관하게 DONE 처리한다")
    void marksDoneWhenPastCap() {
      ScheduledTask task = task(Duration.ofMinutes(1), Duration.ofMinutes(30));
      LocalDateTime afterCap = NOW.plusHours(1);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willReturn(false);

      executor.execute(TASK_ID, afterCap);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.DONE);
    }
  }

  @Nested
  @DisplayName("handler.handle 예외 처리")
  class HandleFailure {

    @Test
    @DisplayName("기본 재시도 정책(RetryPolicy.DEFAULT) 기준으로 1회 실패 시 60초 뒤로 미룬다")
    void appliesDefaultBackoffOnFirstFailure() {
      ScheduledTask task = task(Duration.ofMinutes(1), null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willThrow(new IllegalStateException("boom"));

      executor.execute(TASK_ID, NOW);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
      assertThat(task.getFailureCount()).isEqualTo(1);
      assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("기본 정책 기준 5회 연속 실패하면 FAILED로 격리한다")
    void marksFailedAfterFiveConsecutiveFailuresWithDefaultPolicy() {
      ScheduledTask task = task(Duration.ofMinutes(1), null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willThrow(new IllegalStateException("boom"));

      for (int i = 0; i < 5; i++) {
        executor.execute(TASK_ID, NOW);
      }

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.FAILED);
      assertThat(task.getFailureCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("핸들러가 재시도 정책을 재정의하면(maxFailureCount=2) 2회째에 FAILED로 바뀐다")
    void usesHandlerOverriddenRetryPolicy() {
      ScheduledTask task = task(Duration.ofMinutes(1), null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willThrow(new IllegalStateException("boom"));
      given(handler.retryPolicy())
          .willReturn(new RetryPolicy(2, Duration.ofSeconds(10), Duration.ofMinutes(5)));

      executor.execute(TASK_ID, NOW);
      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
      executor.execute(TASK_ID, NOW);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.FAILED);
      assertThat(task.getFailureCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("cap(end_at)을 이미 넘긴 상태에서 예외가 나면 재시도하지 않고 DONE 처리한다")
    void marksDoneInsteadOfRetryingWhenPastCap() {
      ScheduledTask task = task(Duration.ofMinutes(1), Duration.ofMinutes(30));
      LocalDateTime afterCap = NOW.plusHours(1);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));
      given(handler.handle(REFERENCE_ID)).willThrow(new IllegalStateException("boom"));

      executor.execute(TASK_ID, afterCap);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.DONE);
      assertThat(task.getFailureCount()).isZero();
    }
  }

  @Nested
  @DisplayName("예외적인 조회 결과")
  class EdgeCases {

    @Test
    @DisplayName("등록된 핸들러가 없는 taskType이면 예외 없이 조용히 건너뛴다")
    void skipsSilentlyWhenHandlerMissing() {
      ScheduledTask task = new ScheduledTask("UNKNOWN_TYPE", REFERENCE_ID, NOW, null, null);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      executor.execute(TASK_ID, NOW);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
      verify(handler, never()).handle(anyLong());
    }

    @Test
    @DisplayName("조회 시점에 이미 취소/처리된 task이면 아무것도 하지 않는다")
    void doesNothingWhenTaskGoneOrAlreadyHandled() {
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.empty());

      executor.execute(TASK_ID, NOW);

      verify(handler, never()).handle(anyLong());
    }
  }
}
