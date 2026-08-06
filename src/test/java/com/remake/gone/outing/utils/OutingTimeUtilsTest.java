package com.remake.gone.outing.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link OutingTimeUtils}에 대한 단위 테스트.
 */
class OutingTimeUtilsTest {

  @Nested
  @DisplayName("overlaps")
  class Overlaps {

    @Test
    @DisplayName("두 구간이 완전히 겹치면 true를 반환한다")
    void returnsTrueWhenFullyOverlapping() {
      boolean result = OutingTimeUtils.overlaps(
          LocalTime.of(12, 0), LocalTime.of(14, 0),
          LocalTime.of(12, 30), LocalTime.of(13, 40));

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("두 구간이 부분적으로 겹치면 true를 반환한다")
    void returnsTrueWhenPartiallyOverlapping() {
      boolean result = OutingTimeUtils.overlaps(
          LocalTime.of(12, 30), LocalTime.of(13, 40),
          LocalTime.of(13, 0), LocalTime.of(14, 0));

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("한 구간이 끝나자마자 다음 구간이 시작하면(경계 접촉) 겹치지 않는다")
    void returnsFalseWhenAdjacentAtBoundary() {
      boolean result = OutingTimeUtils.overlaps(
          LocalTime.of(12, 30), LocalTime.of(13, 40),
          LocalTime.of(13, 40), LocalTime.of(14, 40));

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("전혀 겹치지 않는 구간은 false를 반환한다")
    void returnsFalseWhenNotOverlapping() {
      boolean result = OutingTimeUtils.overlaps(
          LocalTime.of(12, 30), LocalTime.of(13, 40),
          LocalTime.of(18, 10), LocalTime.of(21, 10));

      assertThat(result).isFalse();
    }
  }
}
