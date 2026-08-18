package com.remake.gone.schoolcamp.repository;

import com.remake.gone.schoolcamp.entity.SchoolCampSession;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link SchoolCampSession} 리포지토리.
 */
public interface SchoolCampSessionRepository extends JpaRepository<SchoolCampSession, Long> {

  /**
   * 주어진 날짜 중 이미 세션이 등록된 날짜가 있는지 확인합니다.
   *
   * @param campDates 확인할 날짜 목록
   * @return 하나라도 이미 등록되어 있으면 {@code true}
   */
  boolean existsByCampDateIn(List<LocalDate> campDates);

  /**
   * 주어진 기간(양 끝 포함)에 속하는 세션을 전부 조회합니다.
   *
   * @param from 조회 시작일
   * @param to   조회 종료일
   * @return 조건에 맞는 세션 목록
   */
  List<SchoolCampSession> findByCampDateBetween(LocalDate from, LocalDate to);
}
