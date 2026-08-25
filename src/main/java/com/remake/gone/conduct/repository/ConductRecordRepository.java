package com.remake.gone.conduct.repository;

import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.enums.ConductStatus;
import com.remake.gone.conduct.enums.ConductType;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@link ConductRecord} 리포지토리. */
public interface ConductRecordRepository extends JpaRepository<ConductRecord, Long> {

  /**
   * 특정 학생의 특정 종류·상태 기록의 점수 합계를 반환합니다.
   *
   * <p>해당하는 기록이 없으면 {@code 0}을 반환합니다.
   *
   * @param studentUserId 대상 학생 사용자 ID
   * @param type          집계할 종류({@code MERIT} 또는 {@code DEMERIT})
   * @param status        집계할 상태({@code ACTIVE} 또는 {@code CANCELED})
   * @return 점수 합계(부호 포함)
   */
  @Query("SELECT COALESCE(SUM(r.points), 0) FROM ConductRecord r "
      + "WHERE r.student.id = :studentUserId AND r.type = :type AND r.status = :status")
  int sumPointsByStudentAndType(
      @Param("studentUserId") Long studentUserId,
      @Param("type") ConductType type,
      @Param("status") ConductStatus status);

  /**
   * 특정 학생의 상/벌점 이력을 필터·페이지네이션해 반환합니다.
   *
   * <p>{@code type}, {@code dateFrom}, {@code dateTo}가 {@code null}이면 해당 조건을 적용하지
   * 않습니다. 결과는 부여 일시 내림차순으로 정렬됩니다.
   *
   * @param studentUserId 대상 학생 사용자 ID
   * @param type          종류 필터({@code null}이면 전체)
   * @param dateFrom      조회 시작일 필터({@code null}이면 제한 없음)
   * @param dateTo        조회 종료일 필터({@code null}이면 제한 없음)
   * @param pageable      페이지네이션 정보
   * @return 필터링된 기록 페이지
   */
  @Query("SELECT r FROM ConductRecord r "
      + "WHERE r.student.id = :studentUserId "
      + "AND (:type IS NULL OR r.type = :type) "
      + "AND (:dateFrom IS NULL OR CAST(r.createdAt AS LocalDate) >= :dateFrom) "
      + "AND (:dateTo IS NULL OR CAST(r.createdAt AS LocalDate) <= :dateTo) "
      + "ORDER BY r.createdAt DESC")
  Page<ConductRecord> findByStudentWithFilters(
      @Param("studentUserId") Long studentUserId,
      @Param("type") ConductType type,
      @Param("dateFrom") LocalDate dateFrom,
      @Param("dateTo") LocalDate dateTo,
      Pageable pageable);
}
