package com.remake.gone.user.repository;

import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * 로그인 ID 또는 전화번호와 일치하는 계정을 조회합니다. 로그인 시 입력값이 로그인 ID인지
   * 전화번호인지 미리 판별하지 않고 단일 쿼리로 조회하기 위해 사용합니다.
   *
   * @param loginId     로그인 ID로 매칭할 값
   * @param phoneNumber 전화번호로 매칭할 값
   * @return 계정 정보, 없으면 {@link Optional#empty()}
   */
  Optional<User> findFirstByLoginIdOrPhoneNumber(String loginId, String phoneNumber);

  /**
   * 해당 사용자 행에 배타적 락({@code SELECT ... FOR UPDATE})을 걸어 조회합니다. 같은 사용자에
   * 대한 동시 요청을 직렬화해야 하는 로직(예: 외출증 신청의 겹침 검사)에서 사용합니다. 호출하는
   * 메서드는 반드시 {@code @Transactional}이어야 락이 의도한 범위 동안 유지됩니다.
   *
   * @param id 락을 걸고 조회할 사용자 ID
   * @return 사용자 정보, 없으면 {@link Optional#empty()}
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") Long id);
}
