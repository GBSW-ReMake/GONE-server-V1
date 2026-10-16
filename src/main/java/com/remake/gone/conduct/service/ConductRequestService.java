package com.remake.gone.conduct.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.conduct.dto.ConductRequestApproveRequest;
import com.remake.gone.conduct.dto.ConductRequestCreateRequest;
import com.remake.gone.conduct.dto.ConductRequestRejectRequest;
import com.remake.gone.conduct.dto.ConductRequestResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.entity.ConductRequest;
import com.remake.gone.conduct.enums.ConductRequestStatus;
import com.remake.gone.conduct.exception.ConductErrorCode;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import com.remake.gone.conduct.repository.ConductRecordRepository;
import com.remake.gone.conduct.repository.ConductRequestRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상/벌점 요청(ConductRequest) 서비스. */
@Service
@RequiredArgsConstructor
public class ConductRequestService {

  private static final String STUDENT_ROLE_CODE = "STUDENT";
  private static final String TEACHER_ROLE_CODE = "TEACHER";
  private static final String ADMIN_ROLE_CODE = "ADMIN";

  private final ConductRequestRepository conductRequestRepository;
  private final ConductCategoryRepository conductCategoryRepository;
  private final ConductRecordRepository conductRecordRepository;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;

  /**
   * 선도부가 상/벌점 부여를 요청합니다.
   *
   * @param requesterUserId 요청자(DISCIPLINE) 사용자 ID (Access Token에서 추출됨)
   * @param request         요청 생성 정보
   * @return 생성된 상/벌점 요청
   */
  @Transactional
  public ConductRequestResponse createRequest(
      Long requesterUserId, ConductRequestCreateRequest request) {
    ConductCategory category = conductCategoryRepository.findById(request.categoryId())
        .filter(ConductCategory::isActive)
        .orElseThrow(() -> new CustomException(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE));

    User student = userRepository.findById(request.studentUserId())
        .orElseThrow(() -> new CustomException(ConductErrorCode.STUDENT_NOT_FOUND));

    List<String> studentRoles = userRoleRepository.findRoleCodesByUserId(request.studentUserId());
    if (!studentRoles.contains(STUDENT_ROLE_CODE)) {
      throw new CustomException(ConductErrorCode.NOT_STUDENT_ROLE);
    }

    User assignee = userRepository.findById(request.assigneeUserId())
        .orElseThrow(() -> new CustomException(ConductErrorCode.ASSIGNEE_NOT_FOUND));

    List<String> assigneeRoles = userRoleRepository.findRoleCodesByUserId(request.assigneeUserId());
    if (!assigneeRoles.contains(TEACHER_ROLE_CODE) && !assigneeRoles.contains(ADMIN_ROLE_CODE)) {
      throw new CustomException(ConductErrorCode.ASSIGNEE_INVALID_ROLE);
    }

    User requester = userRepository.findById(requesterUserId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.NOT_FOUND));

    ConductRequest conductRequest = ConductRequest.builder()
        .requester(requester)
        .student(student)
        .assignee(assignee)
        .category(category)
        .detail(request.detail())
        .build();

    return ConductRequestResponse.from(conductRequestRepository.save(conductRequest));
  }

  /**
   * 상/벌점 요청 목록을 조회합니다.
   *
   * <p>역할에 따라 조회 범위가 다릅니다.
   * <ul>
   *   <li>DISCIPLINE: 본인이 생성한 요청</li>
   *   <li>TEACHER: 본인에게 배정된 요청</li>
   *   <li>ADMIN: 전체 요청</li>
   * </ul>
   *
   * @param userId 현재 사용자 ID (Access Token에서 추출됨)
   * @param status 상태 필터 (null이면 전체)
   * @param page   페이지 번호 (0-based)
   * @param size   페이지 크기
   * @return 페이지네이션된 요청 목록
   */
  @Transactional(readOnly = true)
  public PageResponse<ConductRequestResponse> getRequests(
      Long userId, ConductRequestStatus status, int page, int size) {
    List<String> roles = userRoleRepository.findRoleCodesByUserId(userId);
    PageRequest pageRequest = PageRequest.of(page, size);
    Page<ConductRequest> result;

    if (roles.contains(ADMIN_ROLE_CODE)) {
      result = status == null
          ? conductRequestRepository.findAllSorted(pageRequest)
          : conductRequestRepository.findByStatus(status, pageRequest);
    } else if (roles.contains(TEACHER_ROLE_CODE)) {
      result = status == null
          ? conductRequestRepository.findByAssigneeId(userId, pageRequest)
          : conductRequestRepository.findByAssigneeIdAndStatus(userId, status, pageRequest);
    } else {
      result = status == null
          ? conductRequestRepository.findByRequesterId(userId, pageRequest)
          : conductRequestRepository.findByRequesterIdAndStatus(userId, status, pageRequest);
    }

    return PageResponse.of(result.map(ConductRequestResponse::from));
  }

  /**
   * 담당자가 상/벌점 요청을 승인하고 {@link ConductRecord}를 생성합니다.
   *
   * <p>TEACHER는 본인에게 배정된 요청만 승인할 수 있습니다. ADMIN은 모든 요청을 승인할 수 있습니다.
   *
   * @param approverUserId 승인자 사용자 ID (Access Token에서 추출됨)
   * @param requestId      승인할 요청 ID
   * @param approveRequest 승인 시 카테고리·사유 오버라이드 (선택)
   * @return 승인된 상/벌점 요청
   */
  @Transactional
  public ConductRequestResponse approveRequest(
      Long approverUserId, Long requestId, ConductRequestApproveRequest approveRequest) {
    ConductRequest conductRequest = conductRequestRepository.findById(requestId)
        .orElseThrow(() -> new CustomException(ConductErrorCode.REQUEST_NOT_FOUND));

    List<String> approverRoles = userRoleRepository.findRoleCodesByUserId(approverUserId);
    if (!approverRoles.contains(ADMIN_ROLE_CODE)) {
      if (!approverUserId.equals(conductRequest.getAssignee().getId())) {
        throw new CustomException(ConductErrorCode.REQUEST_APPROVE_FORBIDDEN);
      }
    }

    if (conductRequest.getStatus() != ConductRequestStatus.PENDING) {
      throw new CustomException(ConductErrorCode.REQUEST_NOT_PROCESSABLE);
    }

    ConductCategory category = conductRequest.getCategory();
    if (approveRequest != null && approveRequest.categoryId() != null) {
      category = conductCategoryRepository.findById(approveRequest.categoryId())
          .filter(ConductCategory::isActive)
          .orElseThrow(() -> new CustomException(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE));
    }

    String detail = (approveRequest != null && approveRequest.detail() != null)
        ? approveRequest.detail()
        : conductRequest.getDetail();

    User approver = userRepository.findById(approverUserId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.NOT_FOUND));

    ConductRecord record = ConductRecord.builder()
        .student(conductRequest.getStudent())
        .teacher(approver)
        .category(category)
        .type(category.getType())
        .points(category.getPoints())
        .detail(detail)
        .build();

    ConductRecord savedRecord = conductRecordRepository.save(record);

    conductRequest.setStatus(ConductRequestStatus.APPROVED);
    conductRequest.setConductRecordId(savedRecord.getId());

    return ConductRequestResponse.from(conductRequest);
  }

  /**
   * 담당자가 상/벌점 요청을 거절합니다.
   *
   * <p>TEACHER는 본인에게 배정된 요청만 거절할 수 있습니다. ADMIN은 모든 요청을 거절할 수 있습니다.
   *
   * @param rejecterUserId 거절자 사용자 ID (Access Token에서 추출됨)
   * @param requestId      거절할 요청 ID
   * @param rejectRequest  거절 사유
   * @return 거절된 상/벌점 요청
   */
  @Transactional
  public ConductRequestResponse rejectRequest(
      Long rejecterUserId, Long requestId, ConductRequestRejectRequest rejectRequest) {
    ConductRequest conductRequest = conductRequestRepository.findById(requestId)
        .orElseThrow(() -> new CustomException(ConductErrorCode.REQUEST_NOT_FOUND));

    List<String> rejecterRoles = userRoleRepository.findRoleCodesByUserId(rejecterUserId);
    if (!rejecterRoles.contains(ADMIN_ROLE_CODE)) {
      if (!rejecterUserId.equals(conductRequest.getAssignee().getId())) {
        throw new CustomException(ConductErrorCode.REQUEST_APPROVE_FORBIDDEN);
      }
    }

    if (conductRequest.getStatus() != ConductRequestStatus.PENDING) {
      throw new CustomException(ConductErrorCode.REQUEST_NOT_PROCESSABLE);
    }

    conductRequest.setStatus(ConductRequestStatus.REJECTED);
    conductRequest.setRejectedReason(rejectRequest.reason());

    return ConductRequestResponse.from(conductRequest);
  }

  /**
   * 선도부가 본인이 등록한 상/벌점 요청을 취소합니다.
   *
   * <p>PENDING 상태의 요청만 취소할 수 있습니다. 취소는 되돌릴 수 없습니다.
   *
   * @param requesterUserId 요청자(DISCIPLINE) 사용자 ID (Access Token에서 추출됨)
   * @param requestId       취소할 요청 ID
   * @return 취소된 상/벌점 요청
   */
  @Transactional
  public ConductRequestResponse cancelRequest(Long requesterUserId, Long requestId) {
    ConductRequest conductRequest = conductRequestRepository.findById(requestId)
        .orElseThrow(() -> new CustomException(ConductErrorCode.REQUEST_NOT_FOUND));

    if (!requesterUserId.equals(conductRequest.getRequester().getId())) {
      throw new CustomException(ConductErrorCode.REQUEST_CANCEL_FORBIDDEN);
    }

    if (conductRequest.getStatus() != ConductRequestStatus.PENDING) {
      throw new CustomException(ConductErrorCode.REQUEST_NOT_CANCELLABLE);
    }

    conductRequest.setStatus(ConductRequestStatus.CANCELED);
    conductRequest.setCanceledAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));

    return ConductRequestResponse.from(conductRequest);
  }
}
