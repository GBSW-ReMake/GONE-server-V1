package com.remake.gone.notification.entity;

import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 알림(Notification) 엔티티.
 *
 * <p>{@code db/migration/V9__add_notification.sql}의 {@code notification} 테이블에 대응한다.
 * 저장은 {@link com.remake.gone.notification.service.NotificationService#send}가 전담하고,
 * 목록 조회와 읽음 처리에서 {@link #isRead}를 사용한다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 알림 수신자. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 100)
  private String title;

  @Column(nullable = false, length = 500)
  private String body;

  /** 알림 분류(프론트엔드 이모지 매핑용, {@link NotificationType} 참고). */
  @Enumerated(EnumType.STRING)
  @Column(length = 50)
  private NotificationType type;

  @Column(name = "is_read", nullable = false)
  private boolean isRead;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
