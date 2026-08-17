package com.remake.gone.notification.enums;

/**
 * 알림 분류.
 *
 * <p>프론트엔드가 이 값 하나당 이모지 1개를 매핑한다 — 이벤트 단위(승인/거절/리마인더
 * 등)가 아니라 도메인 단위로 값을 나눈다. {@code conduct}는 상점과 벌점을 서로 다른
 * 이모지로 구분해야 해서 둘로 나눈다. 같은 도메인 안에서 세부 이벤트가 늘어나도 이 값에는
 * 영향이 없다 — {@code title}/{@code body}가 이벤트별 세부 문구를 담당한다.
 */
public enum NotificationType {
  OUTING,
  SCHOOLCAMP,
  MERIT,
  DEMERIT
}
