package com.remake.gone.common.schedule;

import java.time.Duration;

/**
 * 핸들러 실행 실패 시 재시도 방식을 정의한다. {@code maxFailureCount}번 연속 실패하면
 * FAILED로 격리하고, 그 전까지는 {@code baseBackoff × 2^실패횟수}(최대 {@code maxBackoff})
 * 간격으로 재시도한다(계산 로직은 {@link ScheduledTask#markFailed} 참고).
 */
public record RetryPolicy(int maxFailureCount, Duration baseBackoff, Duration maxBackoff) {

  /** 별도로 재정의하지 않는 모든 핸들러가 쓰는 기본값 — 5회 실패, 30초~30분 백오프. */
  public static final RetryPolicy DEFAULT =
      new RetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(30));
}
