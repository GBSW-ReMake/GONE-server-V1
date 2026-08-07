package com.remake.gone.outing.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.remake.gone.outing.enums.OutingQueryPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OutingQueryPeriodResolver}에 대한 단위 테스트.
 */
class OutingQueryPeriodResolverTest {

  // 2026-08-10은 월요일.
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

  @Test
  @DisplayName("TODAY는 오늘 하루만 범위로 계산한다")
  void resolvesTodayAsSingleDay() {
    OutingDateRange range =
        OutingQueryPeriodResolver.resolve(OutingQueryPeriod.TODAY, TODAY, null, null);

    assertThat(range.from()).isEqualTo(TODAY);
    assertThat(range.to()).isEqualTo(TODAY);
  }

  @Test
  @DisplayName("THIS_WEEK는 오늘이 속한 주의 월요일~일요일로 계산한다")
  void resolvesThisWeekAsMondayToSunday() {
    OutingDateRange range =
        OutingQueryPeriodResolver.resolve(OutingQueryPeriod.THIS_WEEK, TODAY, null, null);

    assertThat(range.from()).isEqualTo(LocalDate.of(2026, 8, 10));
    assertThat(range.to()).isEqualTo(LocalDate.of(2026, 8, 16));
  }

  @Test
  @DisplayName("THIS_WEEK는 오늘이 일요일이어도 그 주(월~일)를 계산한다")
  void resolvesThisWeekWhenTodayIsSunday() {
    LocalDate sunday = LocalDate.of(2026, 8, 16);

    OutingDateRange range =
        OutingQueryPeriodResolver.resolve(OutingQueryPeriod.THIS_WEEK, sunday, null, null);

    assertThat(range.from()).isEqualTo(LocalDate.of(2026, 8, 10));
    assertThat(range.to()).isEqualTo(LocalDate.of(2026, 8, 16));
  }

  @Test
  @DisplayName("THIS_MONTH는 오늘이 속한 달의 1일~말일로 계산한다")
  void resolvesThisMonthAsFirstToLastDay() {
    OutingDateRange range =
        OutingQueryPeriodResolver.resolve(OutingQueryPeriod.THIS_MONTH, TODAY, null, null);

    assertThat(range.from()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(range.to()).isEqualTo(LocalDate.of(2026, 8, 31));
  }

  @Test
  @DisplayName("THIS_MONTH는 30일까지 있는 달도 정확히 말일을 계산한다")
  void resolvesThisMonthForThirtyDayMonth() {
    LocalDate todayInApril = LocalDate.of(2026, 4, 15);

    OutingDateRange range =
        OutingQueryPeriodResolver.resolve(OutingQueryPeriod.THIS_MONTH, todayInApril, null, null);

    assertThat(range.from()).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(range.to()).isEqualTo(LocalDate.of(2026, 4, 30));
  }

  @Test
  @DisplayName("CUSTOM은 전달받은 dateFrom/dateTo를 그대로 사용한다")
  void resolvesCustomUsingGivenDates() {
    LocalDate dateFrom = LocalDate.of(2026, 1, 1);
    LocalDate dateTo = LocalDate.of(2026, 1, 31);

    OutingDateRange range =
        OutingQueryPeriodResolver.resolve(OutingQueryPeriod.CUSTOM, TODAY, dateFrom, dateTo);

    assertThat(range.from()).isEqualTo(dateFrom);
    assertThat(range.to()).isEqualTo(dateTo);
  }
}
