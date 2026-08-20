package com.remake.gone.schoolcamp.repository;

import com.remake.gone.schoolcamp.entity.SchoolCampMember;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link SchoolCampMember} 리포지토리.
 */
public interface SchoolCampMemberRepository extends JpaRepository<SchoolCampMember, Long> {

  /**
   * 주어진 후보 학생들 중, {@code monthStart}~{@code monthEnd}(양 끝 포함) 안에 열리는
   * 세션에 이미(대표/팀원 구분 없이) 유효하게 참여 중인 학생 ID를 조회합니다.
   *
   * <p>대표 신청자로 참여했든 팀원으로 초대받아 참여했든 "이번 달 참여 1회" 제한에 동일하게
   * 걸려야 하므로, {@link SchoolCampMember}를 기준으로 조인한다({@code applicant} 필드가
   * 대표 신청자 본인 행에도 {@code true}로 이미 반영되어 있어 이 테이블 하나로 두 경우를
   * 모두 커버한다).
   *
   * @param candidateIds 확인할 학생 ID 후보(대표 신청자 본인 + 팀원)
   * @param monthStart   조회할 달의 첫날
   * @param monthEnd     조회할 달의 마지막날
   * @return 후보 중 이미 이번 달에 참여 중인 학생 ID 목록(비어있으면 겹침 없음)
   */
  @Query("select m.studentUser.id from SchoolCampMember m "
      + "join m.application a "
      + "join a.session s "
      + "where a.cancelledAt is null "
      + "and m.studentUser.id in :candidateIds "
      + "and s.campDate between :monthStart and :monthEnd")
  List<Long> findParticipatedStudentIdsInMonth(
      @Param("candidateIds") Collection<Long> candidateIds,
      @Param("monthStart") LocalDate monthStart,
      @Param("monthEnd") LocalDate monthEnd);

  /**
   * 한 신청(팀)에 속한 팀원 전체를 조회합니다({@code #70} 수정에서 기존 팀원과의 diff
   * 계산에 사용). 대표 신청자 행({@code applicant=true})도 포함된다.
   *
   * @param applicationId 조회할 신청의 PK
   * @return 그 신청에 속한 팀원 전체
   */
  List<SchoolCampMember> findByApplicationId(Long applicationId);

  /**
   * 본인(대표든 팀원이든)이 참여한 신청 전체를, 취소 여부와 무관하게 최신 순으로
   * 조회합니다({@code #69} 참여 내역 목록). 반환되는 각 행은 "본인"의
   * {@link SchoolCampMember} 행이라 {@link SchoolCampMember#isApplicant()}가 곧 조회를
   * 요청한 본인의 역할이다.
   *
   * <p>{@link #findParticipatedStudentIdsInMonth}(이번 달 중복 참여 검증용)와 같은 조인
   * 모양을 재사용하되, 그 메서드와 달리 {@code cancelledAt IS NULL} 조건을 의도적으로
   * 넣지 않는다 — 취소 이력도 "참여 내역"에 포함해야 하는 이 이슈의 요구사항과
   * 정반대이기 때문이다.
   *
   * @param userId 조회할 학생 사용자 ID
   * @return 본인이 참여한 신청의 본인 팀원 행 목록, {@code campDate} 내림차순
   */
  @Query("select m from SchoolCampMember m "
      + "join fetch m.application a "
      + "join fetch a.session s "
      + "where m.studentUser.id = :userId "
      + "order by s.campDate desc")
  List<SchoolCampMember> findMyParticipations(@Param("userId") Long userId);

  /**
   * {@link #findMyParticipations}와 동일하되, 특정 달({@code monthStart}~{@code monthEnd},
   * 양 끝 포함)로 범위를 한정합니다({@code #69} 참여 내역 목록의 {@code month} 파라미터
   * 지정 시 사용).
   *
   * @param userId     조회할 학생 사용자 ID
   * @param monthStart 조회할 달의 첫날
   * @param monthEnd   조회할 달의 마지막날
   * @return 본인이 그 달에 참여한 신청의 본인 팀원 행 목록, {@code campDate} 내림차순
   */
  @Query("select m from SchoolCampMember m "
      + "join fetch m.application a "
      + "join fetch a.session s "
      + "where m.studentUser.id = :userId "
      + "and s.campDate between :monthStart and :monthEnd "
      + "order by s.campDate desc")
  List<SchoolCampMember> findMyParticipationsInMonth(
      @Param("userId") Long userId,
      @Param("monthStart") LocalDate monthStart,
      @Param("monthEnd") LocalDate monthEnd);
}
