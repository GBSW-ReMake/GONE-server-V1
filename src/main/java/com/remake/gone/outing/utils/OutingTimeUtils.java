package com.remake.gone.outing.utils;

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
}
