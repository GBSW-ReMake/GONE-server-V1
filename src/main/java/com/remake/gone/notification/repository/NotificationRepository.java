package com.remake.gone.notification.repository;

import com.remake.gone.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

  /**
   * 특정 사용자의 읽지 않은 알림을 한 번에 읽음 처리합니다.
   *
   * <p>벌크 UPDATE는 영속 상태의 {@link Notification}을 거치지 않으므로, 실행 전 변경 내용을
   * flush하고 실행 후 영속성 컨텍스트를 비운다. 같은 트랜잭션에서 기존 알림 엔티티를 읽었다면
   * 이 메서드 호출 뒤에는 해당 객체를 재사용하지 말고 다시 조회해야 한다.
   *
   * @param userId 알림 수신자 사용자 ID
   * @return 읽음 처리된 알림 수
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Notification n set n.isRead = true where n.user.id = :userId and n.isRead = false")
  int markAllAsReadByUserId(@Param("userId") Long userId);

  /**
   * 특정 사용자가 읽지 않은 알림 수를 조회합니다.
   *
   * @param userId 알림 수신자 사용자 ID
   * @return 읽지 않은 알림 수
   */
  long countByUserIdAndIsReadFalse(Long userId);
}
