package com.remake.gone.notification.entity;

import com.remake.gone.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 이 이슈(#59)에는 조회/읽음 처리 API가 없다 — {@link #isRead}는 후속 이슈가 그대로 재사용할
 * 수 있도록 미리 포함해둔 컬럼이다.
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

  /**
   * 발송한 도메인이 자유롭게 붙이는 분류 태그(예: {@code "OUTING_APPROVED"}).
   *
   * <p>알림 도메인은 이 값의 의미를 모른다 — {@code outing}/{@code schoolcamp} 같은 구체
   * 도메인의 enum을 참조하면 역방향 의존이 생기므로 일부러 자유 문자열로 둔다.
   */
  @Column(length = 50)
  private String type;

  @Column(name = "is_read", nullable = false)
  private boolean isRead;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
