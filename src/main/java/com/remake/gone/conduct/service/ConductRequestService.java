package com.remake.gone.conduct.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.conduct.dto.ConductRequestCreateRequest;
import com.remake.gone.conduct.dto.ConductRequestResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.entity.ConductRequest;
import com.remake.gone.conduct.enums.ConductRequestStatus;
import com.remake.gone.conduct.exception.ConductErrorCode;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import com.remake.gone.conduct.repository.ConductRequestRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
