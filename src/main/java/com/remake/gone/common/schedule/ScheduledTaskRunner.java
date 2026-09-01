package com.remake.gone.common.schedule;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link ScheduledTask} 폴링 루프(도메인 무관). 트리거 역할만 한다 — 조회는
 * {@link ScheduledTaskRepository}(Spring Data 기본 읽기전용 트랜잭션), 실제 실행/상태 변경은
 * {@link ScheduledTaskExecutor}에 위임한다.
 */
@Component
@RequiredArgsConstructor
public class ScheduledTaskRunner {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final long FIXED_DELAY_MILLIS = 10_000L;

  private final ScheduledTaskRepository scheduledTaskRepository;
  private final ScheduledTaskExecutor scheduledTaskExecutor;

  /**
   * 이전 실행이 끝난 뒤 10초마다 실행됩니다. {@code fixedRate}가 아니라 {@code fixedDelay}를
   * 써서, due 건이 많아 한 틱 처리가 10초를 넘겨도 다음 틱과 겹쳐 실행되지 않게 합니다(#42와
   * 동일한 안전 기본값).
   */
  @Scheduled(fixedDelay = FIXED_DELAY_MILLIS)
  public void run() {
    LocalDateTime now = LocalDateTime.now(KST);
    scheduledTaskRepository.findDueTaskIds(now)
        .forEach(taskId -> scheduledTaskExecutor.execute(taskId, now));
  }
}
