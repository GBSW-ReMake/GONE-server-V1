package com.remake.gone.outing.enums;

import java.util.Set;

/**
 * 외출증 상태.
 *
 * <p>상태 전이: {@code PENDING} -(승인)-&gt; {@code APPROVED} -(출발)-&gt; {@code DEPARTED}
 * -(도착)-&gt; {@code RETURNED}, 또는 {@code PENDING} -(거절)-&gt; {@code REJECTED}.
 */
public enum OutingStatus {
  PENDING,
  APPROVED,
  REJECTED,
  DEPARTED,
  RETURNED;

  /** 아직 종료되지 않아 같은 시간대 중복(겹침) 신청 검사 대상이 되는 상태. */
  public static final Set<OutingStatus> ACTIVE_STATUSES = Set.of(PENDING, APPROVED, DEPARTED);
}
