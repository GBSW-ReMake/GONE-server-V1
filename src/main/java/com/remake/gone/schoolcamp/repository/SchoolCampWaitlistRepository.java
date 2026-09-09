package com.remake.gone.schoolcamp.repository;

import com.remake.gone.schoolcamp.entity.SchoolCampWaitlist;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link SchoolCampWaitlist} 리포지토리.
 */
public interface SchoolCampWaitlistRepository extends JpaRepository<SchoolCampWaitlist, Long> {

  /**
   * 학생 1명의 특정 달 대기 등록 행을 취소 여부와 무관하게 조회합니다(등록/재활성화 판단용).
   *
   * @param studentUserId 조회할 학생 사용자 ID
   * @param month         조회할 달(그 달의 1일)
   * @return 그 학생·그 달의 대기 등록 행(있다면)
   */
  Optional<SchoolCampWaitlist> findByStudentUserIdAndMonth(Long studentUserId, LocalDate month);

  /**
   * 학생 1명의 특정 달 유효한(취소되지 않은) 대기 등록을 조회합니다(취소/상태 조회에서 사용).
   *
   * @param studentUserId 조회할 학생 사용자 ID
   * @param month         조회할 달(그 달의 1일)
   * @return 유효한 대기 등록(있다면)
   */
  Optional<SchoolCampWaitlist> findByStudentUserIdAndMonthAndCancelledAtIsNull(
      Long studentUserId, LocalDate month);

  /**
   * 특정 달의 유효한(취소되지 않은) 대기 등록 전체를 조회합니다(취소 발생 시 알림 발송 대상
   * 조회용).
   *
   * @param month 조회할 달(그 달의 1일)
   * @return 그 달의 유효한 대기 등록 목록
   */
  List<SchoolCampWaitlist> findByMonthAndCancelledAtIsNull(LocalDate month);
}
