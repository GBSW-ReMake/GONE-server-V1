package com.remake.gone.notification.repository;

import com.remake.gone.notification.entity.DeviceToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link DeviceToken} 리포지토리.
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

  /**
   * 사용자의 현재 디바이스 토큰을 조회합니다.
   *
   * @param userId 사용자 ID
   * @return 사용자의 디바이스 토큰, 없으면 빈 값
   */
  Optional<DeviceToken> findByUserId(Long userId);

  /**
   * 사용자의 디바이스 토큰을 삭제합니다.
   *
   * @param userId 사용자 ID
   * @return 삭제된 행 수
   */
  int deleteByUserId(Long userId);
}
