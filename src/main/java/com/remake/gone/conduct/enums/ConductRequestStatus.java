package com.remake.gone.conduct.enums;

/** 상/벌점 요청 상태. */
public enum ConductRequestStatus {
  /** 승인 대기 중. */
  PENDING,
  /** 승인됨 — ConductRecord 생성 완료. */
  APPROVED,
  /** 거절됨. */
  REJECTED,
  /** 요청자(선도부)가 취소함. */
  CANCELED
}
