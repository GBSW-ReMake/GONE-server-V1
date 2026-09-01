package com.remake.gone.common.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link RetryPolicy}에 대한 단위 테스트. 문서에 명시한 기본값(5회, 30초, 30분)이 조용히
 * 바뀌는 회귀를 잡는 용도 및 생성 시점 검증.
 */
class RetryPolicyTest {

  @Test
  @DisplayName("DEFAULT는 5회/30초/30분이다")
  void defaultMatchesDocumentedValues() {
    assertThat(RetryPolicy.DEFAULT.maxFailureCount()).isEqualTo(5);
    assertThat(RetryPolicy.DEFAULT.baseBackoff()).isEqualTo(Duration.ofSeconds(30));
    assertThat(RetryPolicy.DEFAULT.maxBackoff()).isEqualTo(Duration.ofMinutes(30));
  }

  @Nested
  @DisplayName("생성 시점 검증")
  class Validation {

    @Test
    @DisplayName("maxFailureCount가 0 이하면 예외를 던진다")
    void rejectsNonPositiveMaxFailureCount() {
      assertThatThrownBy(
          () -> new RetryPolicy(0, Duration.ofSeconds(30), Duration.ofMinutes(30)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("baseBackoff가 0 이하면 예외를 던진다")
    void rejectsNonPositiveBaseBackoff() {
      assertThatThrownBy(
          () -> new RetryPolicy(5, Duration.ZERO, Duration.ofMinutes(30)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("소수점 초 단위 backoff는 예외를 던진다")
    void rejectsFractionalSecondBackoff() {
      assertThatThrownBy(
          () -> new RetryPolicy(5, Duration.ofMillis(500), Duration.ofMinutes(30)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("maxBackoff가 baseBackoff보다 작으면 예외를 던진다")
    void rejectsMaxBackoffSmallerThanBaseBackoff() {
      assertThatThrownBy(
          () -> new RetryPolicy(5, Duration.ofMinutes(30), Duration.ofSeconds(30)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
