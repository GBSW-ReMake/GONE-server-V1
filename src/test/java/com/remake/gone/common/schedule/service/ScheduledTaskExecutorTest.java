package com.remake.gone.common.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.remake.gone.common.schedule.entity.ScheduledTask;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.repository.ScheduledTaskRepository;
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
 * {@link ScheduledTaskExecutionStore}는 mock하지 않고 mock {@link ScheduledTaskRepository}
 * 위에서 실제로 동작시킨다 — {@code @Transactional} 전파(REQUIRES_NEW 분리, 원자적 claim)
 * 자체는 이 단위 테스트로 검증할 수 없고 {@code ScheduledTaskExecutorIntegrationTest}가
 * 실제 트랜잭션으로 검증하지만, claim/기록 로직의 분기(성공/실패/cap/claim 실패)는
 * mock 리포지토리로도 충분히 검증된다.
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
    // claim()은 status=PENDING인 행을 갱신하는 원자적 UPDATE다 — 이 테스트 스위트의 대부분은
    // "claim 자체는 성공한다"는 전제라 기본값을 1로 깔아두고, claim 실패 시나리오
    // (EdgeCases.doesNothingWhenClaimFails)에서만 개별적으로 0으로 덮어쓴다. lenient()인
    // 이유: 그 테스트에서는 이 기본 스텁이 실제로 쓰이지 않아(재정의로 대체됨) strict
    // stubbing이 "불필요한 스텁"으로 오탐하기 때문이다.
    lenient().when(scheduledTaskRepository.claim(
        eq(TASK_ID), eq(ScheduledTaskStatus.PENDING), any())).thenReturn(1);
    ScheduledTaskExecutionStore executionStore =
        new ScheduledTaskExecutionStore(scheduledTaskRepository);
    executor = new ScheduledTaskExecutor(executionStore, Map.of(TASK_TYPE, handler));
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
    @DisplayName("cap(end_at)을 이미 넘겼으면 handler.handle()을 호출하지 않고 바로 DONE 처리한다")
    void marksDoneWithoutCallingHandlerWhenPastCap() {
      ScheduledTask task = task(Duration.ofMinutes(1), Duration.ofMinutes(30));
      LocalDateTime afterCap = NOW.plusHours(1);
      given(scheduledTaskRepository.findById(TASK_ID)).willReturn(Optional.of(task));

      executor.execute(TASK_ID, afterCap);

      assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.DONE);
      verify(handler, never()).handle(anyLong());
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
    @DisplayName("claim 시점에 이미 취소/처리되어 갱신 대상이 없으면(claim=0) 아무것도 하지 않는다")
    void doesNothingWhenClaimFails() {
      // status=PENDING 조건에 걸리는 행이 없다는 뜻 — cancel()이 먼저 커밋해 행을
      // 지웠거나(#99 코드 리뷰 보류 항목 (b)), 이미 다른 상태로 바뀐 경우다.
      given(scheduledTaskRepository.claim(eq(TASK_ID), eq(ScheduledTaskStatus.PENDING), any()))
          .willReturn(0);

      executor.execute(TASK_ID, NOW);

      verify(handler, never()).handle(anyLong());
    }
  }
}
