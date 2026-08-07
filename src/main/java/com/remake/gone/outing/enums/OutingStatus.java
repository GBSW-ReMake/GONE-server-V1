package com.remake.gone.outing.enums;

import java.util.Set;

/**
 * 외출증 상태.
 *
 * <p>상태 전이: {@code PENDING} -(승인)-&gt; {@code APPROVED} -(출발)-&gt; {@code DEPARTED}
 * -(도착)-&gt; {@code RETURNED}, 또는 {@code PENDING} -(거절)-&gt; {@code REJECTED}.
 *
 * <p>{@code MISSED}는 승인/거절 없이 마감(그 외출증의 {@code outingDate}+{@code startTime})이
 * 지나버린 {@code PENDING}을 뜻한다(#41). DB에는 저장되지 않고(여전히 {@code PENDING}으로
 * 남는다), 조회 응답을 만드는 시점에 실시간으로만 계산돼 끼워진다 — DB에 실제로 반영하는
 * 스케줄러는 #42에서 다룬다.
 */
public enum OutingStatus {
  PENDING,
  APPROVED,
  REJECTED,
  DEPARTED,
  RETURNED,
  MISSED;

  /** 아직 종료되지 않아 같은 시간대 중복(겹침) 신청 검사 대상이 되는 상태. */
  public static final Set<OutingStatus> ACTIVE_STATUSES = Set.of(PENDING, APPROVED, DEPARTED);
}
