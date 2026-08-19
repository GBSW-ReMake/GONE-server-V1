package com.remake.gone.schoolcamp.repository;

import com.remake.gone.schoolcamp.entity.SchoolCampApplication;
import java.util.Collection;
import java.util.List;
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

  /**
   * 여러 세션의 활성(취소되지 않은) 신청을 한 번에 조회합니다. 캘린더 응답
   * ({@code SchoolCampService.getCalendar})이 점유된 세션 수만큼 개별 조회하는 N+1을
   * 피하려고 배치로 묶어 쓴다.
   *
   * @param sessionIds 조회할 세션 PK 목록
   * @return 그 세션들의 활성 신청 목록(세션당 0건 또는 1건)
   */
  List<SchoolCampApplication> findBySessionIdInAndCancelledAtIsNull(Collection<Long> sessionIds);
}
