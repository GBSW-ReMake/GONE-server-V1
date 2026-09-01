package com.remake.gone.common.schedule.service;

import java.time.Duration;

/**
 * 핸들러 실행 실패 시 재시도 방식을 정의한다. {@code maxFailureCount}번 연속 실패하면
 * FAILED로 격리하고, 그 전까지는 {@code baseBackoff × 2^실패횟수}(최대 {@code maxBackoff})
 * 간격으로 재시도한다(계산 로직은 {@code ScheduledTask#markFailed} 참고).
 */
public record RetryPolicy(int maxFailureCount, Duration baseBackoff, Duration maxBackoff) {

  /**
   * {@code ScheduledTask.markFailed}가 이 값들을 검증 없이 그대로 계산에 쓴다 — 여기서
   * 막지 않으면 {@code maxFailureCount<=0}은 첫 실패에 곧장 FAILED로 격리시키고,
   * 0 이하이거나 소수점 초 단위인 backoff는 {@code nextAttemptAt}이 매 폴링마다 계속
   * due 상태이거나 과거로 계산되게 만든다.
   */
  public RetryPolicy {
    if (maxFailureCount <= 0) {
      throw new IllegalArgumentException("maxFailureCount는 1 이상이어야 합니다: " + maxFailureCount);
    }
    requirePositiveWholeSeconds(baseBackoff, "baseBackoff");
    requirePositiveWholeSeconds(maxBackoff, "maxBackoff");
    if (maxBackoff.compareTo(baseBackoff) < 0) {
      throw new IllegalArgumentException(
          "maxBackoff는 baseBackoff 이상이어야 합니다: baseBackoff=" + baseBackoff
              + ", maxBackoff=" + maxBackoff);
    }
  }

  private static void requirePositiveWholeSeconds(Duration duration, String name) {
    if (duration == null) {
      throw new IllegalArgumentException(name + "은 null일 수 없습니다");
    }
    if (duration.isNegative() || duration.isZero() || duration.getNano() != 0) {
      throw new IllegalArgumentException(name + "은 1초 이상의 정수초 단위 Duration이어야 합니다: " + duration);
    }
  }

  /** 별도로 재정의하지 않는 모든 핸들러가 쓰는 기본값 — 5회 실패, 30초~30분 백오프. */
  public static final RetryPolicy DEFAULT =
      new RetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(30));
}
