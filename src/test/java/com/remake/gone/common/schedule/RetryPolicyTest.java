package com.remake.gone.common.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RetryPolicy}에 대한 단위 테스트. 문서에 명시한 기본값(5회, 30초, 30분)이 조용히
 * 바뀌는 회귀를 잡는 용도.
 */
class RetryPolicyTest {

  @Test
  @DisplayName("DEFAULT는 5회/30초/30분이다")
  void defaultMatchesDocumentedValues() {
    assertThat(RetryPolicy.DEFAULT.maxFailureCount()).isEqualTo(5);
    assertThat(RetryPolicy.DEFAULT.baseBackoff()).isEqualTo(Duration.ofSeconds(30));
    assertThat(RetryPolicy.DEFAULT.maxBackoff()).isEqualTo(Duration.ofMinutes(30));
  }
}
