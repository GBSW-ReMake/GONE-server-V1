package com.remake.gone.conduct.repository;

import com.remake.gone.conduct.entity.ConductRequest;
import com.remake.gone.conduct.enums.ConductRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 상/벌점 요청 JPA 레포지토리. */
public interface ConductRequestRepository extends JpaRepository<ConductRequest, Long> {

  /**
   * 특정 요청자가 생성한 요청을 등록 일시 내림차순으로 조회합니다.
   *
   * @param requesterId 요청자 사용자 ID
   * @param pageable    페이지네이션 정보
   * @return 요청 페이지
   */
  @Query("SELECT r FROM ConductRequest r WHERE r.requester.id = :requesterId "
      + "ORDER BY r.createdAt DESC")
  Page<ConductRequest> findByRequesterId(
      @Param("requesterId") Long requesterId, Pageable pageable);

  /**
   * 특정 요청자가 생성한 요청을 상태 필터와 함께 등록 일시 내림차순으로 조회합니다.
   *
   * @param requesterId 요청자 사용자 ID
   * @param status      상태 필터
   * @param pageable    페이지네이션 정보
   * @return 요청 페이지
   */
  @Query("SELECT r FROM ConductRequest r WHERE r.requester.id = :requesterId "
      + "AND r.status = :status ORDER BY r.createdAt DESC")
  Page<ConductRequest> findByRequesterIdAndStatus(
      @Param("requesterId") Long requesterId,
      @Param("status") ConductRequestStatus status,
      Pageable pageable);

  /**
   * 특정 담당자에게 배정된 요청을 등록 일시 내림차순으로 조회합니다.
   *
   * @param assigneeId 담당자 사용자 ID
   * @param pageable   페이지네이션 정보
   * @return 요청 페이지
   */
  @Query("SELECT r FROM ConductRequest r WHERE r.assignee.id = :assigneeId "
      + "ORDER BY r.createdAt DESC")
  Page<ConductRequest> findByAssigneeId(
      @Param("assigneeId") Long assigneeId, Pageable pageable);

  /**
   * 특정 담당자에게 배정된 요청을 상태 필터와 함께 등록 일시 내림차순으로 조회합니다.
   *
   * @param assigneeId 담당자 사용자 ID
   * @param status     상태 필터
   * @param pageable   페이지네이션 정보
   * @return 요청 페이지
   */
  @Query("SELECT r FROM ConductRequest r WHERE r.assignee.id = :assigneeId "
      + "AND r.status = :status ORDER BY r.createdAt DESC")
  Page<ConductRequest> findByAssigneeIdAndStatus(
      @Param("assigneeId") Long assigneeId,
      @Param("status") ConductRequestStatus status,
      Pageable pageable);

  /**
   * 전체 요청을 등록 일시 내림차순으로 조회합니다 (ADMIN 용).
   *
   * @param pageable 페이지네이션 정보
   * @return 요청 페이지
   */
  @Query("SELECT r FROM ConductRequest r ORDER BY r.createdAt DESC")
  Page<ConductRequest> findAllSorted(Pageable pageable);

  /**
   * 전체 요청을 상태 필터와 함께 등록 일시 내림차순으로 조회합니다 (ADMIN 용).
   *
   * @param status   상태 필터
   * @param pageable 페이지네이션 정보
   * @return 요청 페이지
   */
  @Query("SELECT r FROM ConductRequest r WHERE r.status = :status ORDER BY r.createdAt DESC")
  Page<ConductRequest> findByStatus(
      @Param("status") ConductRequestStatus status, Pageable pageable);
}
