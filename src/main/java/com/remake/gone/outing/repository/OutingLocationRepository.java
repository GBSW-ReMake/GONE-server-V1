package com.remake.gone.outing.repository;

import com.remake.gone.outing.entity.OutingLocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link OutingLocation} 리포지토리.
 */
public interface OutingLocationRepository extends JpaRepository<OutingLocation, Long> {

  /**
   * 특정 외출증의 위치 핑을 기록된 시각 오름차순으로 조회합니다(#97, 동선 조회용).
   *
   * @param outingId 조회할 외출증의 내부 PK
   * @return 조건에 맞는 위치 핑 목록, {@code recordedAt} 오름차순
   */
  List<OutingLocation> findByOutingIdOrderByRecordedAtAsc(Long outingId);
}
