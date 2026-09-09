package com.remake.gone.user.repository;

import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
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

  /**
   * 실명(Gbsw.name)에 검색어가 부분 일치하는 가입된 사용자를 조회합니다. 아직 가입하지 않은
   * 명단(Gbsw) 레코드는 대응하는 {@link User}가 없어 결과에 포함되지 않습니다. 졸업/자퇴·퇴학
   * 계정({@link UserStatus#ACTIVE}가 아닌 계정)도 결과에서 제외됩니다.
   *
   * <p>{@code query}는 호출하는 쪽({@code UserService})에서 LIKE 와일드카드(`%`/`_`)를 이스케이프
   * 처리해서 넘겨야 한다 — 이 메서드는 그 값을 그대로 `%...%`로 감싸기만 한다.
   *
   * @param query LIKE 와일드카드가 이스케이프 처리된 검색어
   * @return 조건에 맞는 사용자 목록
   */
  default List<User> searchByRealNameContaining(String query) {
    return searchByRealNameContainingAndStatus(query, UserStatus.ACTIVE);
  }

  /**
   * {@link #searchByRealNameContaining(String)}가 위임하는 실제 쿼리. {@code status}를
   * 파라미터 바인딩으로 받아, 상태 조건이 필요하면 이 메서드를 직접 호출할 수도 있다.
   *
   * @param query  LIKE 와일드카드가 이스케이프 처리된 검색어
   * @param status 결과에 포함할 사용자 상태
   * @return 조건에 맞는 사용자 목록
   */
  @Query("select u from User u join fetch u.gbsw g "
      + "where g.name like concat('%', :query, '%') escape '\\' "
      + "and u.status = :status")
  List<User> searchByRealNameContainingAndStatus(
      @Param("query") String query, @Param("status") UserStatus status);

  /**
   * 실명 또는 학번에 검색어가 부분 일치하는 가입된 사용자의 ID를 조회합니다. 역할 필터는
   * 없습니다. N+1을 막기 위해 ID만 반환하며, 엔티티 fetch는 {@link #findAllByIdWithGbsw(List)}로
   * 수행합니다.
   *
   * <p>JPQL {@code function()} 래퍼가 반환 타입을 {@code Object}로 추론해 Hibernate 7 타입
   * 검증을 통과하지 못하므로, 학번 계산({@code LPAD}, {@code CONCAT})이 필요한 이 쿼리에 한해
   * 네이티브 SQL을 사용합니다. 프로젝트의 다른 쿼리는 JPQL을 사용합니다.
   *
   * <p>{@code query}는 호출하는 쪽({@code UserService})에서 LIKE 와일드카드를 이스케이프 처리해서
   * 넘겨야 합니다. {@code status}는 {@link UserStatus#name()}으로 변환해서 넘겨야 합니다.
   *
   * @param query  LIKE 와일드카드가 이스케이프 처리된 검색어
   * @param status 결과에 포함할 사용자 상태 문자열(예: {@code "ACTIVE"})
   * @return 조건에 맞는 사용자 ID 목록
   */
  @Query(value = "select u.id from user u join gbsw g on u.gbsw_id = g.id "
      + "where u.status = :status "
      + "and (g.name like concat('%', :query, '%') escape '\\\\' "
      + "  or (g.number is not null "
      + "      and concat(g.grade, g.class_no, lpad(g.number, 2, '0')) "
      + "          like concat('%', :query, '%') escape '\\\\'))",
      nativeQuery = true)
  List<Long> findIdsByQuery(@Param("query") String query, @Param("status") String status);

  /**
   * 실명 또는 학번에 검색어가 부분 일치하면서 지정된 역할 중 하나 이상을 가진 사용자의 ID를
   * 조회합니다. {@code roles}는 비어 있지 않아야 합니다(빈 목록이면
   * {@link #findIdsByQuery(String, String)}를 사용하세요).
   *
   * <p>네이티브 SQL 사용 이유는 {@link #findIdsByQuery(String, String)} 참고.
   *
   * <p>{@code query}는 호출하는 쪽({@code UserService})에서 LIKE 와일드카드를 이스케이프 처리해서
   * 넘겨야 합니다. {@code status}는 {@link UserStatus#name()}으로 변환해서 넘겨야 합니다.
   *
   * @param query  LIKE 와일드카드가 이스케이프 처리된 검색어
   * @param roles  역할 코드 목록(비어 있으면 안 됨)
   * @param status 결과에 포함할 사용자 상태 문자열(예: {@code "ACTIVE"})
   * @return 조건에 맞는 사용자 ID 목록
   */
  @Query(value = "select u.id from user u join gbsw g on u.gbsw_id = g.id "
      + "where u.status = :status "
      + "and (g.name like concat('%', :query, '%') escape '\\\\' "
      + "  or (g.number is not null "
      + "      and concat(g.grade, g.class_no, lpad(g.number, 2, '0')) "
      + "          like concat('%', :query, '%') escape '\\\\')) "
      + "and exists ("
      + "  select 1 from user_role ur join role r on ur.role_id = r.id "
      + "  where ur.user_id = u.id and r.code in :roles)",
      nativeQuery = true)
  List<Long> findIdsByQueryAndRoles(
      @Param("query") String query,
      @Param("roles") List<String> roles,
      @Param("status") String status);

  /**
   * ID 목록으로 사용자를 조회하면서 {@link com.remake.gone.gbsw.entity.Gbsw}를 즉시 로딩합니다.
   * N+1을 막기 위해 {@link #findIdsByQuery(String, String)}/{@link
   * #findIdsByQueryAndRoles(String, List, String)} 결과로 받은 ID 목록을 이 메서드에 넘겨
   * 최종 엔티티를 가져옵니다.
   *
   * @param ids 조회할 사용자 ID 목록
   * @return {@link com.remake.gone.gbsw.entity.Gbsw}가 즉시 로딩된 사용자 목록
   */
  @Query("select u from User u join fetch u.gbsw g where u.id in :ids")
  List<User> findAllByIdWithGbsw(@Param("ids") List<Long> ids);
}
