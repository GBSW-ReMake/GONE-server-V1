package com.remake.gone.outing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OutingTimeSlot}에 대한 단위 테스트.
 */
class OutingTimeSlotTest {

  @Test
  @DisplayName("LUNCH는 12:30~13:40이다")
  void lunchHasExpectedRange() {
    assertThat(OutingTimeSlot.LUNCH.getStartTime()).isEqualTo(LocalTime.of(12, 30));
    assertThat(OutingTimeSlot.LUNCH.getEndTime()).isEqualTo(LocalTime.of(13, 40));
  }

  @Test
  @DisplayName("DINNER 종료 시각은 19:10이다(#43 버그 회귀 방지 — 21:10으로 되돌아가지 않도록)")
  void dinnerEndTimeIsNineteenTen() {
    assertThat(OutingTimeSlot.DINNER.getStartTime()).isEqualTo(LocalTime.of(18, 10));
    assertThat(OutingTimeSlot.DINNER.getEndTime()).isEqualTo(LocalTime.of(19, 10));
  }

  @Test
  @DisplayName("커스텀 시간대 허용 범위는 08:40~20:30이다")
  void customWindowHasExpectedRange() {
    assertThat(OutingTimeSlot.CUSTOM_WINDOW_START).isEqualTo(LocalTime.of(8, 40));
    assertThat(OutingTimeSlot.CUSTOM_WINDOW_END).isEqualTo(LocalTime.of(20, 30));
  }
}
