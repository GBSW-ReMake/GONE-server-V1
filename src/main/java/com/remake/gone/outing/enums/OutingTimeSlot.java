package com.remake.gone.outing.enums;

import java.time.LocalTime;

/**
 * 외출 시간대.
 *
 * <p>{@code LUNCH}/{@code DINNER}는 서버가 시작/종료 시각을 고정하는 프리셋이고, {@code CUSTOM}은
 * 학생이 직접 시각을 입력하는 경우다(허용 범위는 {@link #CUSTOM_WINDOW_START}~
 * {@link #CUSTOM_WINDOW_END}, 지속시간 제한은 없음).
 */
public enum OutingTimeSlot {
  LUNCH(LocalTime.of(12, 30), LocalTime.of(13, 40)),
  DINNER(LocalTime.of(18, 10), LocalTime.of(21, 10)),
  CUSTOM(null, null);

  /** 커스텀 시간대의 허용 시작 하한(08:30). */
  public static final LocalTime CUSTOM_WINDOW_START = LocalTime.of(8, 30);

  /** 커스텀 시간대의 허용 종료 상한(21:10, {@code DINNER} 종료 시각과 동일). */
  public static final LocalTime CUSTOM_WINDOW_END = LocalTime.of(21, 10);

  private final LocalTime startTime;
  private final LocalTime endTime;

  OutingTimeSlot(LocalTime startTime, LocalTime endTime) {
    this.startTime = startTime;
    this.endTime = endTime;
  }

  /**
   * 이 시간대가 서버가 시작/종료 시각을 고정하는 프리셋인지 확인한다.
   *
   * @return 프리셋({@code LUNCH}/{@code DINNER})이면 {@code true}, {@code CUSTOM}이면
   *         {@code false}
   */
  public boolean isPreset() {
    return this != CUSTOM;
  }

  /**
   * 프리셋 시작 시각을 반환한다. {@code CUSTOM}에는 호출하지 않는다.
   *
   * @return 프리셋 시작 시각
   */
  public LocalTime getStartTime() {
    return startTime;
  }

  /**
   * 프리셋 종료 시각을 반환한다. {@code CUSTOM}에는 호출하지 않는다.
   *
   * @return 프리셋 종료 시각
   */
  public LocalTime getEndTime() {
    return endTime;
  }
}
