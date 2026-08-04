package com.remake.gone.user.repository;

import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link User} 리포지토리.
 */
public interface UserRepository extends JpaRepository<User, Long> {

  /**
   * 해당 명단(Gbsw) 레코드에 이미 연결된 계정이 있는지 확인합니다.
   *
   * @param gbsw 확인할 명단 레코드
   * @return 이미 연결된 계정이 있으면 {@code true}
   */
  boolean existsByGbsw(Gbsw gbsw);

  /**
   * 해당 로그인 ID를 사용 중인 계정이 있는지 확인합니다.
   *
   * @param loginId 확인할 로그인 ID
   * @return 이미 사용 중이면 {@code true}
   */
  boolean existsByLoginId(String loginId);

  /**
   * 해당 별명을 사용 중인 계정이 있는지 확인합니다.
   *
   * @param name 확인할 별명
   * @return 이미 사용 중이면 {@code true}
   */
  boolean existsByName(String name);

  /**
   * 로그인 ID로 계정을 조회합니다.
   *
   * @param loginId 조회할 로그인 ID
   * @return 계정 정보, 없으면 {@link Optional#empty()}
   */
  Optional<User> findByLoginId(String loginId);
}
