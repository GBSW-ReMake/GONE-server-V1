package com.remake.gone.outing.repository;

import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Outing} 리포지토리.
 */
public interface OutingRepository extends JpaRepository<Outing, Long> {

  /**
   * 외부 식별자 코드로 외출증을 조회합니다.
   *
   * @param code 조회할 코드
   * @return 외출증 정보, 없으면 {@link Optional#empty()}
   */
  Optional<Outing> findByCode(String code);

  /**
   * 특정 학생의 특정 날짜에, 주어진 상태들에 해당하는 외출증을 조회합니다. 시간 겹침(중복 신청)
   * 검사에 사용합니다.
   *
   * @param studentId  학생 사용자 ID
   * @param outingDate 외출 날짜
   * @param statuses   조회할 상태 목록
   * @return 조건에 맞는 외출증 목록
   */
  List<Outing> findByStudentIdAndOutingDateAndStatusIn(
      Long studentId, LocalDate outingDate, Collection<OutingStatus> statuses);

  /**
   * 특정 학생이 신청한, 주어진 날짜 범위(양 끝 포함) 안의 외출증을 조회합니다(#41).
   *
   * @param studentId 학생 사용자 ID
   * @param dateFrom  범위 시작일
   * @param dateTo    범위 종료일
   * @return 조건에 맞는 외출증 목록, 날짜/시작 시각 오름차순
   */
  List<Outing> findByStudentIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(
      Long studentId, LocalDate dateFrom, LocalDate dateTo);

  /**
   * 특정 선생님에게 담당으로 지정된, 주어진 날짜 범위(양 끝 포함) 안의 외출증을 조회합니다(#41).
   *
   * @param teacherId 선생님 사용자 ID
   * @param dateFrom  범위 시작일
   * @param dateTo    범위 종료일
   * @return 조건에 맞는 외출증 목록, 날짜/시작 시각 오름차순
   */
  List<Outing> findByTeacherIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(
      Long teacherId, LocalDate dateFrom, LocalDate dateTo);
}
