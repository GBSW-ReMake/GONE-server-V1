package com.remake.gone.outing.repository;

import com.remake.gone.outing.entity.OutingLocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link OutingLocation} 리포지토리.
 */
public interface OutingLocationRepository extends JpaRepository<OutingLocation, Long> {

  /**
   * 특정 외출증의 위치 핑을 기록된 시각 오름차순으로 조회합니다(#97, 동선 조회용). {@code
   * recorded_at}이 초 단위 정밀도라 같은 초에 들어온 핑이 있을 수 있어, {@code id}(삽입 순서와
   * 일치)를 보조 정렬 키로 둔다 — 이 서비스의 다른 목록 조회들과 같은 이유({@code
   * OutingService}의 {@code LIST_QUERY_SORT} 등 참고).
   *
   * @param outingId 조회할 외출증의 내부 PK
   * @return 조건에 맞는 위치 핑 목록, {@code recordedAt} 오름차순(동률 시 {@code id} 오름차순)
   */
  List<OutingLocation> findByOutingIdOrderByRecordedAtAscIdAsc(Long outingId);
}
