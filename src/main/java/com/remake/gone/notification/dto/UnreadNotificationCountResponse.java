package com.remake.gone.notification.dto;

/**
 * 안 읽은 알림 개수 조회 응답 DTO.
 *
 * @param unreadCount 읽지 않은 알림 수
 */
public record UnreadNotificationCountResponse(long unreadCount) {}
