package com.remake.gone.schoolcamp.enums;

/**
 * 스쿨캠핑 세션(날짜)의 점유 상태.
 */
public enum SchoolCampStatus {
  /** 아직 신청이 없어 비어있는 날짜. */
  OPEN,
  /** 이미 다른 팀이 신청해 마감된 날짜. */
  CLOSED
}
