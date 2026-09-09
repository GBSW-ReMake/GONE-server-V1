package com.remake.gone.notification.dto;

import com.remake.gone.notification.entity.Notification;
import com.remake.gone.notification.enums.NotificationType;
import java.time.LocalDateTime;

/**
 * 알림 목록 조회 응답 DTO.
 *
 * @param id        알림 식별자
 * @param title     알림 제목
 * @param body      알림 본문
 * @param type      알림 도메인 타입
 * @param isRead    읽음 여부
 * @param createdAt 알림 생성 시각
 */
public record NotificationResponse(
    Long id,
    String title,
    String body,
    NotificationType type,
    boolean isRead,
    LocalDateTime createdAt
) {

  /**
   * 알림 엔티티를 목록 조회 응답으로 변환합니다.
   *
   * @param notification 변환할 알림
   * @return 변환된 알림 응답
   */
  public static NotificationResponse from(Notification notification) {
    return new NotificationResponse(
        notification.getId(),
        notification.getTitle(),
        notification.getBody(),
        notification.getType(),
        notification.isRead(),
        notification.getCreatedAt());
  }
}
