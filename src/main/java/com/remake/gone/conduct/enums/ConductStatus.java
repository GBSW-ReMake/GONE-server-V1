package com.remake.gone.conduct.enums;

/** 상/벌점 기록 상태. */
public enum ConductStatus {
  /** 유효한 기록(집계에 포함). */
  ACTIVE,
  /** 취소된 기록(집계에서 제외, 이력에는 남아 표시). */
  CANCELED
}
