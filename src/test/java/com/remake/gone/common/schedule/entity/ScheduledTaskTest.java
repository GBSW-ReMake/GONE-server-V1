package com.remake.gone.common.schedule.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.service.RetryPolicy;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ScheduledTask} 생성 시점의 {@code interval} 검증과 {@code retry()}(#126)에 대한
 * 단위 테스트. 다른 상태 전이(markDone/markSucceeded/markFailed)는
 * {@code ScheduledTaskExecutorTest}가 다룬다.
 */
class ScheduledTaskTest {

  private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 9, 1, 15, 0);

  @Test
  @DisplayName("interval이 null이면 1회성 작업으로 허용한다")
  void allowsNullInterval() {
    assertThatCode(() -> new ScheduledTask("TYPE", 1L, SCHEDULED_AT, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("interval이 0이면 예외를 던진다")
  void rejectsZeroInterval() {
    assertThatThrownBy(() -> new ScheduledTask("TYPE", 1L, SCHEDULED_AT, Duration.ZERO, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("interval이 음수면 예외를 던진다")
  void rejectsNegativeInterval() {
    assertThatThrownBy(
        () -> new ScheduledTask("TYPE", 1L, SCHEDULED_AT, Duration.ofSeconds(-1), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("interval이 소수점 초 단위면 예외를 던진다")
  void rejectsFractionalSecondInterval() {
    assertThatThrownBy(
        () -> new ScheduledTask("TYPE", 1L, SCHEDULED_AT, Duration.ofMillis(500), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("retry()는 상태를 PENDING으로, 실패 이력을 초기화하고 nextAttemptAt을 넘긴 시각으로 되돌린다")
  void retryResetsStateToPending() {
    ScheduledTask task = new ScheduledTask("TYPE", 1L, SCHEDULED_AT, Duration.ofMinutes(1), null);
    RetryPolicy policy = RetryPolicy.DEFAULT;
    task.markFailed(SCHEDULED_AT, "boom", policy.maxFailureCount(),
        policy.baseBackoff(), policy.maxBackoff());
    LocalDateTime retryAt = SCHEDULED_AT.plusHours(1);

    task.retry(retryAt);

    assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
    assertThat(task.getFailureCount()).isZero();
    assertThat(task.getLastError()).isNull();
    assertThat(task.getNextAttemptAt()).isEqualTo(retryAt);
  }
}
