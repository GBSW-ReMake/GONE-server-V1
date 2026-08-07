package com.remake.gone.outing.enums;

/**
 * 외출증 조회 기간 프리셋(#41).
 *
 * <p>{@code TODAY}/{@code THIS_WEEK}/{@code THIS_MONTH}는 서버가 실제 날짜 범위를 계산하는
 * 고정 프리셋이고, {@code CUSTOM}만 클라이언트가 {@code dateFrom}/{@code dateTo}를 직접
 * 지정한다 — {@link OutingTimeSlot}(프리셋 + {@code CUSTOM})과 같은 패턴이다.
 */
public enum OutingQueryPeriod {
  TODAY,
  THIS_WEEK,
  THIS_MONTH,
  CUSTOM
}
