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
    // 조회 기준 시각은 한 번만 고정한다 — 이 틱에서 "무엇이 due인지"의 기준은 틱 시작
    // 시점이어야 일관된다. 반면 각 task를 실제로 처리하는 시각(execute에 넘기는 now)은
    // task마다 다시 구한다 — due 건이 많아 앞 task 처리가 오래 걸리면, 뒤 task는 틱 시작
    // 시각보다 실제로 몇 초 늦게 처리되는데 그 stale한 시각으로 cap 판정/백오프를 계산하면
    // 안 되기 때문이다.
    LocalDateTime tickStartedAt = LocalDateTime.now(KST);
    scheduledTaskRepository.findDueTaskIds(ScheduledTaskStatus.PENDING, tickStartedAt)
        .forEach(taskId -> scheduledTaskExecutor.execute(taskId, LocalDateTime.now(KST)));
  }
}
