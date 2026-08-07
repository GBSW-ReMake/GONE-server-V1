package com.remake.gone.outing.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

  @Nested
  @DisplayName("isPastDeadline")
  class IsPastDeadline {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    @Test
    @DisplayName("외출 날짜가 오늘보다 과거면 true를 반환한다")
    void returnsTrueWhenOutingDateBeforeToday() {
      boolean result = OutingTimeUtils.isPastDeadline(
          LocalDate.of(2026, 8, 9), LocalTime.of(12, 30), TODAY, LocalTime.of(9, 0));

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("오늘 날짜이고 지금 시각이 시작 시각과 같으면 true를 반환한다")
    void returnsTrueWhenNowEqualsStartTime() {
      boolean result = OutingTimeUtils.isPastDeadline(
          TODAY, LocalTime.of(12, 30), TODAY, LocalTime.of(12, 30));

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("오늘 날짜이고 지금 시각이 시작 시각을 지났으면 true를 반환한다")
    void returnsTrueWhenNowAfterStartTime() {
      boolean result = OutingTimeUtils.isPastDeadline(
          TODAY, LocalTime.of(12, 30), TODAY, LocalTime.of(13, 0));

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("오늘 날짜이고 지금 시각이 시작 시각 전이면 false를 반환한다")
    void returnsFalseWhenNowBeforeStartTime() {
      boolean result = OutingTimeUtils.isPastDeadline(
          TODAY, LocalTime.of(12, 30), TODAY, LocalTime.of(9, 0));

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("외출 날짜가 오늘보다 미래면 false를 반환한다")
    void returnsFalseWhenOutingDateAfterToday() {
      boolean result = OutingTimeUtils.isPastDeadline(
          LocalDate.of(2026, 8, 11), LocalTime.of(9, 0), TODAY, LocalTime.of(23, 0));

      assertThat(result).isFalse();
    }
  }
}
