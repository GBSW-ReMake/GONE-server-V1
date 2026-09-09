package com.remake.gone.outing.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.remake.gone.outing.service.OutingService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OutingTimeoutScheduledTaskHandler}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class OutingTimeoutScheduledTaskHandlerTest {

  private static final Long OUTING_ID = 500L;

  @Mock
  private OutingService outingService;

  @InjectMocks
  private OutingTimeoutScheduledTaskHandler handler;

  @Test
  @DisplayName("CONTINUE면 아직 재실행이 필요하므로 handle이 false를 반환한다")
  void returnsFalseWhenStillContinuing() {
    given(outingService.checkAndNotifyTimeout(eq(OUTING_ID), any(LocalDateTime.class)))
        .willReturn(OutingService.TimeoutCheckResult.CONTINUE);

    boolean done = handler.handle(OUTING_ID);

    assertThat(done).isFalse();
  }

  @Test
  @DisplayName("RETURNED_OR_MISSING이면 더 이상 재실행할 필요가 없으므로 handle이 true를 반환한다")
  void returnsTrueWhenReturnedOrMissing() {
    given(outingService.checkAndNotifyTimeout(eq(OUTING_ID), any(LocalDateTime.class)))
        .willReturn(OutingService.TimeoutCheckResult.RETURNED_OR_MISSING);

    boolean done = handler.handle(OUTING_ID);

    assertThat(done).isTrue();
  }
}
