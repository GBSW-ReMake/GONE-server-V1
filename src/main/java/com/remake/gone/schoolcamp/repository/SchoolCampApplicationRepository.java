package com.remake.gone.schoolcamp.repository;

import com.remake.gone.schoolcamp.entity.SchoolCampApplication;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
   * 활성(취소되지 않은) 신청을 PK로 조회합니다({@code #70} 취소/수정에서 사용). 이미 취소된
   * 신청은 없는 것처럼 취급한다 — 재취소·재수정 시도를 조회 단계에서부터 걸러낸다.
   *
   * @param id 조회할 신청의 PK
   * @return 활성 신청(있다면)
   */
  Optional<SchoolCampApplication> findByIdAndCancelledAtIsNull(Long id);

  /**
   * 여러 세션의 활성(취소되지 않은) 신청을 한 번에 조회합니다. 캘린더 응답
   * ({@code SchoolCampService.getCalendar})이 점유된 세션 수만큼 개별 조회하는 N+1을
   * 피하려고 배치로 묶어 쓴다.
   *
   * @param sessionIds 조회할 세션 PK 목록
   * @return 그 세션들의 활성 신청 목록(세션당 0건 또는 1건)
   */
  List<SchoolCampApplication> findBySessionIdInAndCancelledAtIsNull(Collection<Long> sessionIds);

  /**
   * 특정 날짜에 열리는 세션의 활성(취소되지 않은) 신청을 조회합니다(#71 외출 연동 리마인더
   * 스케줄러가 오늘 참여하는 신청을 찾을 때 사용). 한 세션에 성사되는 신청은
   * 정확히 1건뿐이라 항상 0개 또는 1개가 조회된다.
   *
   * @param campDate 조회할 캠핑 날짜
   * @return 그 날짜 세션의 활성 신청(있다면)
   */
  List<SchoolCampApplication> findBySessionCampDateAndCancelledAtIsNull(LocalDate campDate);

  /**
   * 본인이 담당 선생님으로 지정된 신청 전체를, 취소 여부와 무관하게 최신 순으로
   * 조회합니다(#69 참여 내역 목록의 선생님 쪽 이력).
   *
   * @param teacherUserId 조회할 선생님 사용자 ID
   * @return 그 선생님이 담당인 신청 목록, {@code campDate} 내림차순
   */
  @Query("select a from SchoolCampApplication a "
      + "join fetch a.session s "
      + "where a.teacherUser.id = :teacherUserId "
      + "order by s.campDate desc")
  List<SchoolCampApplication> findByTeacherUserId(@Param("teacherUserId") Long teacherUserId);

  /**
   * {@link #findByTeacherUserId}와 동일하되, 특정 달({@code monthStart}~{@code monthEnd},
   * 양 끝 포함)로 범위를 한정합니다(#69 참여 내역 목록의 {@code month} 파라미터 지정 시
   * 사용).
   *
   * @param teacherUserId 조회할 선생님 사용자 ID
   * @param monthStart    조회할 달의 첫날
   * @param monthEnd      조회할 달의 마지막날
   * @return 그 선생님이 그 달에 담당인 신청 목록, {@code campDate} 내림차순
   */
  @Query("select a from SchoolCampApplication a "
      + "join fetch a.session s "
      + "where a.teacherUser.id = :teacherUserId "
      + "and s.campDate between :monthStart and :monthEnd "
      + "order by s.campDate desc")
  List<SchoolCampApplication> findByTeacherUserIdInMonth(
      @Param("teacherUserId") Long teacherUserId,
      @Param("monthStart") LocalDate monthStart,
      @Param("monthEnd") LocalDate monthEnd);
}
