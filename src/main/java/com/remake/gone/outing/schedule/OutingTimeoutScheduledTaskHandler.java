package com.remake.gone.outing.schedule;

import com.remake.gone.common.schedule.ScheduledTaskHandler;
import com.remake.gone.outing.service.OutingService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 외출 시간 초과 리마인더의 {@code common/schedule}(#120) 접점(#99). {@code
 * OutingService.departOuting}이 {@code ScheduledTaskService.schedule("OUTING_TIMEOUT", ...)}로
 * 등록한 task를 #120의 {@code ScheduledTaskRunner}가 폴링 루프에서 이 빈을 찾아 호출한다 —
 * 실제 도메인 로직({@link OutingService#checkAndNotifyTimeout})은 스케줄링 방식과 무관한
 * 순수 서비스 메서드다.
 */
@Component("OUTING_TIMEOUT")
@RequiredArgsConstructor
public class OutingTimeoutScheduledTaskHandler implements ScheduledTaskHandler {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final OutingService outingService;

  @Override
  public boolean handle(Long outingId) {
    OutingService.TimeoutCheckResult result =
        outingService.checkAndNotifyTimeout(outingId, LocalDateTime.now(KST));
    return result == OutingService.TimeoutCheckResult.RETURNED_OR_MISSING;
  }
}
