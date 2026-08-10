package com.remake.gone.outing.scheduler;

import com.remake.gone.outing.service.OutingService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 마감이 지난 {@code PENDING} 외출증을 DB에서 실제로 {@code MISSED}로 갱신하는 백그라운드
 * 스케줄러(#42). 이 프로젝트에서 처음 쓰는 {@code @Scheduled} 컴포넌트다.
 */
@Component
@RequiredArgsConstructor
public class OutingMissedScheduler {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final long FIXED_DELAY_MILLIS = 60_000L;

  private final OutingService outingService;

  /**
   * 이전 실행이 끝난 뒤 1분마다 실행됩니다. {@code fixedRate}가 아니라 {@code fixedDelay}를
   * 써서, 실행 시간이 늘어나도 다음 실행과 겹치지 않게 합니다.
   */
  @Scheduled(fixedDelay = FIXED_DELAY_MILLIS)
  public void markOverdueOutingsAsMissed() {
    LocalDateTime now = LocalDateTime.now(KST);
    outingService.markOverdueOutingsAsMissed(now.toLocalDate(), now.toLocalTime());
  }
}
