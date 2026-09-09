package com.remake.gone.role.repository;

import com.remake.gone.role.entity.Role;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Role} 리포지토리.
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

  /**
   * 역할 코드로 역할을 조회합니다.
   *
   * @param code 조회할 역할 코드 (예: {@code STUDENT})
   * @return 일치하는 {@link Role}, 없으면 빈 {@link Optional}
   */
  Optional<Role> findByCode(String code);

  /**
   * 해당 역할 코드가 존재하는지 확인합니다.
   *
   * @param code 확인할 역할 코드
   * @return 존재하면 {@code true}
   */
  boolean existsByCode(String code);
}
