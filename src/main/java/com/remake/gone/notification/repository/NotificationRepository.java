package com.remake.gone.notification.repository;

import com.remake.gone.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Notification} 리포지토리.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
