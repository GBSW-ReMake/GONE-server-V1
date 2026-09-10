package com.remake.gone.conduct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.conduct.dto.ConductRequestApproveRequest;
import com.remake.gone.conduct.dto.ConductRequestCreateRequest;
import com.remake.gone.conduct.dto.ConductRequestResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.entity.ConductRequest;
import com.remake.gone.conduct.enums.ConductRequestStatus;
import com.remake.gone.conduct.enums.ConductType;
import com.remake.gone.conduct.exception.ConductErrorCode;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import com.remake.gone.conduct.repository.ConductRecordRepository;
import com.remake.gone.conduct.repository.ConductRequestRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * {@link ConductRequestService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ConductRequestServiceTest {

  @Mock
  private ConductRequestRepository conductRequestRepository;

  @Mock
  private ConductCategoryRepository conductCategoryRepository;

  @Mock
  private ConductRecordRepository conductRecordRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserRoleRepository userRoleRepository;

  @InjectMocks
  private ConductRequestService conductRequestService;

  private ConductCategory category(Long id) {
    return ConductCategory.builder()
        .id(id)
        .label("지각")
        .type(ConductType.DEMERIT)
        .points(-1)
        .active(true)
        .build();
  }

  private ConductRequest pendingRequest(Long id, Long requesterId, Long assigneeId) {
    return ConductRequest.builder()
        .id(id)
        .requester(User.builder().id(requesterId).name("홍선도").build())
        .student(User.builder().id(101L).name("길동이").build())
        .assignee(User.builder().id(assigneeId).name("김선생").build())
        .category(category(5L))
        .detail("3교시 10분 지각")
        .build();
  }

  @Nested
  @DisplayName("createRequest")
  class CreateRequest {

    private final Long requesterUserId = 33L;
    private final Long studentUserId = 101L;
    private final Long assigneeUserId = 42L;
    private final Long categoryId = 5L;

    private ConductRequestCreateRequest createRequest() {
      return new ConductRequestCreateRequest(
          studentUserId, assigneeUserId, categoryId, "3교시 10분 지각");
    }

    @Test
    @DisplayName("정상 요청 시 PENDING 상태의 ConductRequest를 저장하고 응답 DTO를 반환한다")
    void createsRequestAndReturnsPendingStatus() {
      given(conductCategoryRepository.findById(categoryId))
          .willReturn(Optional.of(category(categoryId)));
      given(userRepository.findById(studentUserId))
          .willReturn(Optional.of(User.builder().id(studentUserId).name("길동이").build()));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId))
          .willReturn(List.of("STUDENT"));
      given(userRepository.findById(assigneeUserId))
          .willReturn(Optional.of(User.builder().id(assigneeUserId).name("김선생").build()));
      given(userRoleRepository.findRoleCodesByUserId(assigneeUserId))
          .willReturn(List.of("TEACHER"));
      given(userRepository.findById(requesterUserId))
          .willReturn(Optional.of(User.builder().id(requesterUserId).name("홍선도").build()));

      ConductRequest saved = ConductRequest.builder()
          .id(1L)
          .requester(User.builder().id(requesterUserId).name("홍선도").build())
          .student(User.builder().id(studentUserId).name("길동이").build())
          .assignee(User.builder().id(assigneeUserId).name("김선생").build())
          .category(category(categoryId))
          .detail("3교시 10분 지각")
          .build();
      given(conductRequestRepository.save(any(ConductRequest.class))).willReturn(saved);

      ConductRequestResponse result =
          conductRequestService.createRequest(requesterUserId, createRequest());

      assertThat(result.status()).isEqualTo(ConductRequestStatus.PENDING);
      assertThat(result.studentUserId()).isEqualTo(studentUserId);
      assertThat(result.assigneeUserId()).isEqualTo(assigneeUserId);
      assertThat(result.categoryId()).isEqualTo(categoryId);
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 ID이면 CONDUCT_004 예외를 던진다")
    void throwsWhenCategoryNotFound() {
      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.empty());

      assertThatThrownBy(
          () -> conductRequestService.createRequest(requesterUserId, createRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE);
    }

    @Test
    @DisplayName("비활성 카테고리이면 CONDUCT_004 예외를 던진다")
    void throwsWhenCategoryInactive() {
      ConductCategory inactive = ConductCategory.builder()
          .id(categoryId).label("지각").type(ConductType.DEMERIT).points(-1).active(false).build();
      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(inactive));

      assertThatThrownBy(
          () -> conductRequestService.createRequest(requesterUserId, createRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE);
    }

    @Test
    @DisplayName("존재하지 않는 학생 ID이면 CONDUCT_005 예외를 던진다")
    void throwsWhenStudentNotFound() {
      given(conductCategoryRepository.findById(categoryId))
          .willReturn(Optional.of(category(categoryId)));
      given(userRepository.findById(studentUserId)).willReturn(Optional.empty());

      assertThatThrownBy(
          () -> conductRequestService.createRequest(requesterUserId, createRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.STUDENT_NOT_FOUND);
    }

    @Test
    @DisplayName("대상 사용자가 STUDENT 역할이 아니면 CONDUCT_006 예외를 던진다")
    void throwsWhenTargetNotStudent() {
      given(conductCategoryRepository.findById(categoryId))
          .willReturn(Optional.of(category(categoryId)));
      given(userRepository.findById(studentUserId))
          .willReturn(Optional.of(User.builder().id(studentUserId).name("길동이").build()));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId))
          .willReturn(List.of("TEACHER"));

      assertThatThrownBy(
          () -> conductRequestService.createRequest(requesterUserId, createRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.NOT_STUDENT_ROLE);
    }

    @Test
    @DisplayName("존재하지 않는 배정 대상자 ID이면 CONDUCT_012 예외를 던진다")
    void throwsWhenAssigneeNotFound() {
      given(conductCategoryRepository.findById(categoryId))
          .willReturn(Optional.of(category(categoryId)));
      given(userRepository.findById(studentUserId))
          .willReturn(Optional.of(User.builder().id(studentUserId).name("길동이").build()));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId)).willReturn(List.of("STUDENT"));
      given(userRepository.findById(assigneeUserId)).willReturn(Optional.empty());

      assertThatThrownBy(
          () -> conductRequestService.createRequest(requesterUserId, createRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.ASSIGNEE_NOT_FOUND);
    }

    @Test
    @DisplayName("배정 대상자가 TEACHER·ADMIN 역할이 아니면 CONDUCT_013 예외를 던진다")
    void throwsWhenAssigneeInvalidRole() {
      given(conductCategoryRepository.findById(categoryId))
          .willReturn(Optional.of(category(categoryId)));
      given(userRepository.findById(studentUserId))
          .willReturn(Optional.of(User.builder().id(studentUserId).name("길동이").build()));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId)).willReturn(List.of("STUDENT"));
      given(userRepository.findById(assigneeUserId))
          .willReturn(Optional.of(User.builder().id(assigneeUserId).name("김선생").build()));
      given(userRoleRepository.findRoleCodesByUserId(assigneeUserId))
          .willReturn(List.of("STUDENT"));

      assertThatThrownBy(
          () -> conductRequestService.createRequest(requesterUserId, createRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.ASSIGNEE_INVALID_ROLE);
    }
  }

  @Nested
  @DisplayName("getRequests")
  class GetRequests {

    private final Long userId = 42L;

    @Test
    @DisplayName("ADMIN이면 전체 요청을 조회한다")
    void returnsAllRequestsForAdmin() {
      ConductRequest req = pendingRequest(1L, 33L, userId);
      given(userRoleRepository.findRoleCodesByUserId(userId)).willReturn(List.of("ADMIN"));
      given(conductRequestRepository.findAllSorted(any()))
          .willReturn(new PageImpl<>(List.of(req), PageRequest.of(0, 20), 1));

      PageResponse<ConductRequestResponse> result =
          conductRequestService.getRequests(userId, null, 0, 20);

      assertThat(result.content()).hasSize(1);
    }

    @Test
    @DisplayName("ADMIN이면 status 필터를 적용해 조회한다")
    void returnsFilteredRequestsForAdmin() {
      ConductRequest req = pendingRequest(1L, 33L, userId);
      given(userRoleRepository.findRoleCodesByUserId(userId)).willReturn(List.of("ADMIN"));
      given(conductRequestRepository.findByStatus(any(ConductRequestStatus.class), any()))
          .willReturn(new PageImpl<>(List.of(req), PageRequest.of(0, 20), 1));

      PageResponse<ConductRequestResponse> result =
          conductRequestService.getRequests(userId, ConductRequestStatus.PENDING, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).status()).isEqualTo(ConductRequestStatus.PENDING);
    }

    @Test
    @DisplayName("TEACHER이면 본인에게 배정된 요청만 조회한다")
    void returnsAssignedRequestsForTeacher() {
      ConductRequest req = pendingRequest(1L, 33L, userId);
      given(userRoleRepository.findRoleCodesByUserId(userId)).willReturn(List.of("TEACHER"));
      given(conductRequestRepository.findByAssigneeId(any(), any()))
          .willReturn(new PageImpl<>(List.of(req), PageRequest.of(0, 20), 1));

      PageResponse<ConductRequestResponse> result =
          conductRequestService.getRequests(userId, null, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).assigneeUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("TEACHER이면 status 필터를 적용해 배정된 요청만 조회한다")
    void returnsFilteredAssignedRequestsForTeacher() {
      ConductRequest req = pendingRequest(1L, 33L, userId);
      given(userRoleRepository.findRoleCodesByUserId(userId)).willReturn(List.of("TEACHER"));
      given(conductRequestRepository.findByAssigneeIdAndStatus(
          any(), any(ConductRequestStatus.class), any()))
          .willReturn(new PageImpl<>(List.of(req), PageRequest.of(0, 20), 1));

      PageResponse<ConductRequestResponse> result =
          conductRequestService.getRequests(userId, ConductRequestStatus.PENDING, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).status()).isEqualTo(ConductRequestStatus.PENDING);
    }

    @Test
    @DisplayName("DISCIPLINE이면 본인이 생성한 요청만 조회한다")
    void returnsOwnRequestsForDiscipline() {
      Long disciplineId = 33L;
      ConductRequest req = pendingRequest(1L, disciplineId, 42L);
      given(userRoleRepository.findRoleCodesByUserId(disciplineId))
          .willReturn(List.of("DISCIPLINE"));
      given(conductRequestRepository.findByRequesterId(any(), any()))
          .willReturn(new PageImpl<>(List.of(req), PageRequest.of(0, 20), 1));

      PageResponse<ConductRequestResponse> result =
          conductRequestService.getRequests(disciplineId, null, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).requesterUserId()).isEqualTo(disciplineId);
    }

    @Test
    @DisplayName("DISCIPLINE이면 status 필터를 적용해 본인이 생성한 요청만 조회한다")
    void returnsFilteredOwnRequestsForDiscipline() {
      Long disciplineId = 33L;
      ConductRequest req = pendingRequest(1L, disciplineId, 42L);
      given(userRoleRepository.findRoleCodesByUserId(disciplineId))
          .willReturn(List.of("DISCIPLINE"));
      given(conductRequestRepository.findByRequesterIdAndStatus(
          any(), any(ConductRequestStatus.class), any()))
          .willReturn(new PageImpl<>(List.of(req), PageRequest.of(0, 20), 1));

      PageResponse<ConductRequestResponse> result =
          conductRequestService.getRequests(disciplineId, ConductRequestStatus.PENDING, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).status()).isEqualTo(ConductRequestStatus.PENDING);
    }
  }

  @Nested
  @DisplayName("approveRequest")
  class ApproveRequest {

    private final Long assigneeId = 42L;
    private final Long requestId = 1L;

    @Test
    @DisplayName("TEACHER(assignee)가 승인하면 APPROVED 상태가 되고 conductRecordId가 세팅된다")
    void approvesRequestAndCreatesRecord() {
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);
      ConductRecord savedRecord = ConductRecord.builder()
          .id(7L).student(req.getStudent()).teacher(req.getAssignee())
          .category(req.getCategory()).type(ConductType.DEMERIT).points(-1).build();

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(assigneeId)).willReturn(List.of("TEACHER"));
      given(userRepository.findById(assigneeId))
          .willReturn(Optional.of(User.builder().id(assigneeId).name("김선생").build()));
      given(conductRecordRepository.save(any(ConductRecord.class))).willReturn(savedRecord);

      ConductRequestResponse result =
          conductRequestService.approveRequest(assigneeId, requestId, null);

      assertThat(result.status()).isEqualTo(ConductRequestStatus.APPROVED);
      assertThat(result.conductRecordId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("ADMIN이면 assignee가 아니어도 승인할 수 있다")
    void adminCanApproveAnyRequest() {
      Long adminId = 99L;
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);
      ConductRecord savedRecord = ConductRecord.builder()
          .id(8L).student(req.getStudent()).teacher(req.getAssignee())
          .category(req.getCategory()).type(ConductType.DEMERIT).points(-1).build();

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(adminId)).willReturn(List.of("ADMIN"));
      given(userRepository.findById(adminId))
          .willReturn(Optional.of(User.builder().id(adminId).name("관리자").build()));
      given(conductRecordRepository.save(any(ConductRecord.class))).willReturn(savedRecord);

      ConductRequestResponse result =
          conductRequestService.approveRequest(adminId, requestId, null);

      assertThat(result.status()).isEqualTo(ConductRequestStatus.APPROVED);
    }

    @Test
    @DisplayName("카테고리 오버라이드가 있으면 오버라이드 카테고리로 ConductRecord를 생성한다")
    void usesCategoryOverrideWhenProvided() {
      Long overrideCategoryId = 10L;
      ConductCategory override = ConductCategory.builder()
          .id(overrideCategoryId).label("봉사").type(ConductType.MERIT).points(3).active(true)
          .build();
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);
      ConductRecord savedRecord = ConductRecord.builder()
          .id(9L).student(req.getStudent()).teacher(req.getAssignee())
          .category(override).type(ConductType.MERIT).points(3).build();

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(assigneeId)).willReturn(List.of("TEACHER"));
      given(conductCategoryRepository.findById(overrideCategoryId))
          .willReturn(Optional.of(override));
      given(userRepository.findById(assigneeId))
          .willReturn(Optional.of(User.builder().id(assigneeId).name("김선생").build()));
      given(conductRecordRepository.save(any(ConductRecord.class))).willReturn(savedRecord);

      ConductRequestResponse result = conductRequestService.approveRequest(
          assigneeId, requestId, new ConductRequestApproveRequest(overrideCategoryId, null));

      assertThat(result.status()).isEqualTo(ConductRequestStatus.APPROVED);
      ArgumentCaptor<ConductRecord> captor = ArgumentCaptor.forClass(ConductRecord.class);
      verify(conductRecordRepository).save(captor.capture());
      assertThat(captor.getValue().getCategory().getId()).isEqualTo(overrideCategoryId);
      assertThat(captor.getValue().getType()).isEqualTo(ConductType.MERIT);
      assertThat(captor.getValue().getPoints()).isEqualTo(3);
    }

    @Test
    @DisplayName("detail 오버라이드가 있으면 해당 사유로 ConductRecord를 생성한다")
    void usesDetailOverrideWhenProvided() {
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);
      ConductRecord savedRecord = ConductRecord.builder()
          .id(11L).student(req.getStudent()).teacher(req.getAssignee())
          .category(req.getCategory()).type(ConductType.DEMERIT).points(-1).detail("오버라이드 사유")
          .build();

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(assigneeId)).willReturn(List.of("TEACHER"));
      given(userRepository.findById(assigneeId))
          .willReturn(Optional.of(User.builder().id(assigneeId).name("김선생").build()));
      given(conductRecordRepository.save(any(ConductRecord.class))).willReturn(savedRecord);

      ConductRequestResponse result = conductRequestService.approveRequest(
          assigneeId, requestId, new ConductRequestApproveRequest(null, "오버라이드 사유"));

      assertThat(result.status()).isEqualTo(ConductRequestStatus.APPROVED);
      ArgumentCaptor<ConductRecord> captor = ArgumentCaptor.forClass(ConductRecord.class);
      verify(conductRecordRepository).save(captor.capture());
      assertThat(captor.getValue().getDetail()).isEqualTo("오버라이드 사유");
    }

    @Test
    @DisplayName("존재하지 않는 요청 ID이면 CONDUCT_009 예외를 던진다")
    void throwsWhenRequestNotFound() {
      given(conductRequestRepository.findById(requestId)).willReturn(Optional.empty());

      assertThatThrownBy(
          () -> conductRequestService.approveRequest(assigneeId, requestId, null))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("TEACHER가 assignee가 아니면 CONDUCT_014 예외를 던진다")
    void throwsWhenTeacherNotAssignee() {
      Long otherId = 99L;
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(otherId)).willReturn(List.of("TEACHER"));

      assertThatThrownBy(
          () -> conductRequestService.approveRequest(otherId, requestId, null))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_APPROVE_FORBIDDEN);
    }

    @Test
    @DisplayName("PENDING이 아닌 상태이면 CONDUCT_015 예외를 던진다")
    void throwsWhenNotPending() {
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);
      req.setStatus(ConductRequestStatus.CANCELED);

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(assigneeId)).willReturn(List.of("TEACHER"));

      assertThatThrownBy(
          () -> conductRequestService.approveRequest(assigneeId, requestId, null))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_NOT_PROCESSABLE);
    }

    @Test
    @DisplayName("오버라이드 카테고리가 inactive이면 CONDUCT_004 예외를 던진다")
    void throwsWhenOverrideCategoryInactive() {
      Long overrideCategoryId = 10L;
      ConductCategory inactive = ConductCategory.builder()
          .id(overrideCategoryId).label("비활성").type(ConductType.MERIT).points(3).active(false)
          .build();
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(assigneeId)).willReturn(List.of("TEACHER"));
      given(conductCategoryRepository.findById(overrideCategoryId))
          .willReturn(Optional.of(inactive));

      assertThatThrownBy(() -> conductRequestService.approveRequest(
          assigneeId, requestId, new ConductRequestApproveRequest(overrideCategoryId, null)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE);
    }
  }

  @Nested
  @DisplayName("rejectRequest")
  class RejectRequest {

    private final Long assigneeId = 42L;
    private final Long requestId = 1L;

    @Test
    @DisplayName("TEACHER(assignee)가 거절하면 REJECTED 상태로 전환된다")
    void rejectsRequestByTeacher() {
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(assigneeId)).willReturn(List.of("TEACHER"));

      ConductRequestResponse result =
          conductRequestService.rejectRequest(assigneeId, requestId);

      assertThat(result.status()).isEqualTo(ConductRequestStatus.REJECTED);
    }

    @Test
    @DisplayName("ADMIN이면 assignee가 아니어도 거절할 수 있다")
    void adminCanRejectAnyRequest() {
      Long adminId = 99L;
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(adminId)).willReturn(List.of("ADMIN"));

      ConductRequestResponse result =
          conductRequestService.rejectRequest(adminId, requestId);

      assertThat(result.status()).isEqualTo(ConductRequestStatus.REJECTED);
    }

    @Test
    @DisplayName("존재하지 않는 요청 ID이면 CONDUCT_009 예외를 던진다")
    void throwsWhenRequestNotFound() {
      given(conductRequestRepository.findById(requestId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> conductRequestService.rejectRequest(assigneeId, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("TEACHER가 assignee가 아니면 CONDUCT_014 예외를 던진다")
    void throwsWhenTeacherNotAssignee() {
      Long otherId = 99L;
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(otherId)).willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> conductRequestService.rejectRequest(otherId, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_APPROVE_FORBIDDEN);
    }

    @Test
    @DisplayName("PENDING이 아닌 상태이면 CONDUCT_015 예외를 던진다")
    void throwsWhenNotPending() {
      ConductRequest req = pendingRequest(requestId, 33L, assigneeId);
      req.setStatus(ConductRequestStatus.APPROVED);

      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(req));
      given(userRoleRepository.findRoleCodesByUserId(assigneeId)).willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> conductRequestService.rejectRequest(assigneeId, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_NOT_PROCESSABLE);
    }
  }

  @Nested
  @DisplayName("cancelRequest")
  class CancelRequest {

    private final Long requesterUserId = 33L;
    private final Long requestId = 1L;

    @Test
    @DisplayName("PENDING 요청을 취소하면 CANCELED 상태로 전환되고 canceledAt이 세팅된다")
    void cancelsPendingRequest() {
      ConductRequest pending = pendingRequest(requestId, requesterUserId, 42L);
      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(pending));

      ConductRequestResponse result =
          conductRequestService.cancelRequest(requesterUserId, requestId);

      assertThat(result.status()).isEqualTo(ConductRequestStatus.CANCELED);
      assertThat(pending.getCanceledAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 요청 ID이면 CONDUCT_009 예외를 던진다")
    void throwsWhenRequestNotFound() {
      given(conductRequestRepository.findById(requestId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> conductRequestService.cancelRequest(requesterUserId, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("요청자 본인이 아니면 CONDUCT_010 예외를 던진다")
    void throwsWhenNotOwner() {
      given(conductRequestRepository.findById(requestId))
          .willReturn(Optional.of(pendingRequest(requestId, requesterUserId, 42L)));

      assertThatThrownBy(() -> conductRequestService.cancelRequest(999L, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_CANCEL_FORBIDDEN);
    }

    @Test
    @DisplayName("PENDING이 아닌 상태이면 CONDUCT_011 예외를 던진다")
    void throwsWhenNotPending() {
      ConductRequest approved = pendingRequest(requestId, requesterUserId, 42L);
      approved.setStatus(ConductRequestStatus.APPROVED);
      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(approved));

      assertThatThrownBy(() -> conductRequestService.cancelRequest(requesterUserId, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_NOT_CANCELLABLE);
    }
  }
}
