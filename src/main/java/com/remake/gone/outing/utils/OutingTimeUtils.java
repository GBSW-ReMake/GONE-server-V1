package com.remake.gone.outing.utils;

import java.time.LocalDate;
import java.time.LocalTime;

/** 외출 시간 구간 계산을 위한 순수 함수 모음. */
public final class OutingTimeUtils {

  private OutingTimeUtils() {
  }

  /**
   * 두 시간 구간이 겹치는지 확인합니다. 각 구간은 시작 시각 이상, 종료 시각 미만({@code [start,
   * end)})으로 취급합니다.
   *
   * @param start1 첫 번째 구간 시작 시각
   * @param end1   첫 번째 구간 종료 시각
   * @param start2 두 번째 구간 시작 시각
   * @param end2   두 번째 구간 종료 시각
   * @return 두 구간이 조금이라도 겹치면 {@code true}
   */
  public static boolean overlaps(
      LocalTime start1, LocalTime end1, LocalTime start2, LocalTime end2) {
    return start1.isBefore(end2) && start2.isBefore(end1);
  }

  /**
   * 외출증의 시작 시각(마감)이 이미 지났는지 확인합니다(#41 {@code MISSED} 판정에 사용).
   *
   * <p>{@link com.remake.gone.outing.service.OutingService}의 신청 시점 마감 검증과 같은
   * 개념이지만, 신청 검증은 과거 날짜 자체를 별도로 먼저 막아 "오늘보다 이전 날짜"인 경우를
   * 다룰 필요가 없었다. 이 함수는 이미 저장된 {@code PENDING} 건을 대상으로 하므로 그 경우도
   * 함께 판단한다.
   *
   * @param outingDate 외출 날짜
   * @param startTime  그 외출증의 시작 시각
   * @param today      "오늘" 날짜(KST)
   * @param now        "지금" 시각(KST)
   * @return 외출 날짜가 오늘보다 이전이거나, 오늘이면서 시작 시각이 이미 지났으면 {@code true}
   */
  public static boolean isPastDeadline(
      LocalDate outingDate, LocalTime startTime, LocalDate today, LocalTime now) {
    return outingDate.isBefore(today)
        || (outingDate.isEqual(today) && !now.isBefore(startTime));
  }
}
