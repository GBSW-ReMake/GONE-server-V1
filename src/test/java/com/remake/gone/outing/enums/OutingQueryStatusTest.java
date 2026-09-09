package com.remake.gone.outing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OutingQueryStatus}에 대한 단위 테스트.
 */
class OutingQueryStatusTest {

  @Test
  @DisplayName("toOutingStatus는 이름이 같은 OutingStatus 값으로 변환한다")
  void convertsToOutingStatusWithSameName() {
    assertThat(OutingQueryStatus.PENDING.toOutingStatus()).isEqualTo(OutingStatus.PENDING);
    assertThat(OutingQueryStatus.APPROVED.toOutingStatus()).isEqualTo(OutingStatus.APPROVED);
    assertThat(OutingQueryStatus.REJECTED.toOutingStatus()).isEqualTo(OutingStatus.REJECTED);
    assertThat(OutingQueryStatus.DEPARTED.toOutingStatus()).isEqualTo(OutingStatus.DEPARTED);
    assertThat(OutingQueryStatus.RETURNED.toOutingStatus()).isEqualTo(OutingStatus.RETURNED);
    assertThat(OutingQueryStatus.MISSED.toOutingStatus()).isEqualTo(OutingStatus.MISSED);
  }
}
