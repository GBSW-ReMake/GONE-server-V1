package com.remake.gone.schoolcamp.repository;

import com.remake.gone.schoolcamp.entity.SchoolCampApplication;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link SchoolCampApplication} 리포지토리.
 */
public interface SchoolCampApplicationRepository
    extends JpaRepository<SchoolCampApplication, Long> {

  /**
   * 세션의 활성(취소되지 않은) 신청을 조회합니다. 한 세션에 성사되는 신청은 정확히 1건뿐이라
   * 이 조건으로 걸리는 행은 항상 0개 또는 1개다.
   *
   * @param sessionId 조회할 세션의 PK
   * @return 그 세션의 활성 신청(있다면)
   */
  Optional<SchoolCampApplication> findBySessionIdAndCancelledAtIsNull(Long sessionId);
}
