package com.remake.gone.role.repository;

import com.remake.gone.role.entity.UserRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link UserRole} 리포지토리.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

  /**
   * 특정 사용자가 가진 역할들의 코드 목록을 조회합니다.
   *
   * @param userId 조회할 사용자 ID
   * @return 역할 코드 목록 (예: {@code ["STUDENT", "DISCIPLINE"]})
   */
  @Query("select ur.role.code from UserRole ur where ur.user.id = :userId")
  List<String> findRoleCodesByUserId(@Param("userId") Long userId);
}
