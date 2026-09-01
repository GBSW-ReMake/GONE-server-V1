package com.remake.gone.common.schedule.enums;

/** {@code ScheduledTask}의 실행 상태. */
public enum ScheduledTaskStatus {
  /** 대기 또는 재시도 중. */
  PENDING,
  /** 정상 종료. */
  DONE,
  /** 재시도 상한 초과, 수동 개입 필요. */
  FAILED
}
