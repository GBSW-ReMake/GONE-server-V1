package com.remake.gone.notification.repository;

import com.remake.gone.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link Notification} 리포지토리.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

  /**
   * 특정 사용자가 받은 알림을 페이지 단위로 조회합니다.
   *
   * @param userId   알림 수신자 사용자 ID
   * @param pageable 페이지 번호, 크기, 정렬 정보
   * @return 사용자의 알림 페이지
   */
  @Query("select n from Notification n where n.user.id = :userId")
  Page<Notification> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
