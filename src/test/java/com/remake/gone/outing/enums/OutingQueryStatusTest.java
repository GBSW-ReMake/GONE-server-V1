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
    assertThat(OutingQueryStatus.MISSED.toOutingStatus()).isEqualTo(OutingStatus.MISSED);
  }

  @Test
  @DisplayName("DEPARTED/RETURNED는 필터 값으로 존재하지 않는다")
  void doesNotIncludeUnreachableStatuses() {
    assertThat(OutingQueryStatus.values())
        .extracting(Enum::name)
        .doesNotContain("DEPARTED", "RETURNED");
  }
}
