package com.remake.gone.outing.utils;

import com.remake.gone.outing.enums.OutingQueryPeriod;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * {@link OutingQueryPeriod} 프리셋을 실제 날짜 범위로 계산하는 순수 함수 모음(#41).
 *
 * <p>입력값 검증(예: {@code CUSTOM}인데 {@code dateFrom}/{@code dateTo}가 없는 경우)은 이
 * 유틸리티의 책임이 아니다 — 이 프로젝트의 다른 유틸(예: {@link OutingTimeUtils})과 마찬가지로
 * 순수 계산만 담당하고, {@code CustomException}을 던지는 검증은 항상 서비스 코드가 한다. 이미
 * 유효한 조합이 들어온다고 가정한다.
 */
public final class OutingQueryPeriodResolver {

  private OutingQueryPeriodResolver() {
  }

  /**
   * 조회 기간 프리셋을 실제 {@code [dateFrom, dateTo]} 범위로 계산합니다.
   *
   * @param period    조회 기간 프리셋
   * @param today     "오늘" 날짜(KST)
   * @param dateFrom  {@code period == CUSTOM}일 때만 사용하는 시작일(그 외엔 {@code null})
   * @param dateTo    {@code period == CUSTOM}일 때만 사용하는 종료일(그 외엔 {@code null})
   * @return 계산된 날짜 범위
   */
  public static OutingDateRange resolve(
      OutingQueryPeriod period, LocalDate today, LocalDate dateFrom, LocalDate dateTo) {
    return switch (period) {
      case TODAY -> new OutingDateRange(today, today);
      case THIS_WEEK -> new OutingDateRange(
          today.with(DayOfWeek.MONDAY), today.with(DayOfWeek.SUNDAY));
      case THIS_MONTH -> {
        YearMonth thisMonth = YearMonth.from(today);
        yield new OutingDateRange(thisMonth.atDay(1), thisMonth.atEndOfMonth());
      }
      case CUSTOM -> new OutingDateRange(dateFrom, dateTo);
    };
  }
}
