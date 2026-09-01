package com.remake.gone.conduct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.conduct.dto.ConductRequestCreateRequest;
import com.remake.gone.conduct.dto.ConductRequestResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.entity.ConductRequest;
import com.remake.gone.conduct.enums.ConductRequestStatus;
import com.remake.gone.conduct.enums.ConductType;
import com.remake.gone.conduct.exception.ConductErrorCode;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
  private UserRepository userRepository;

  @Mock
  private UserRoleRepository userRoleRepository;

  @InjectMocks
  private ConductRequestService conductRequestService;

  @Nested
  @DisplayName("createRequest")
  class CreateRequest {

    private final Long requesterUserId = 33L;
    private final Long studentUserId = 101L;
    private final Long assigneeUserId = 42L;
    private final Long categoryId = 5L;

    private ConductCategory category() {
      return ConductCategory.builder()
          .id(categoryId)
          .label("지각")
          .type(ConductType.DEMERIT)
          .points(-1)
          .active(true)
          .build();
    }

    private User requester() {
      return User.builder().id(requesterUserId).name("홍선도").build();
    }

    private User student() {
      return User.builder().id(studentUserId).name("길동이").build();
    }

    private User assignee() {
      return User.builder().id(assigneeUserId).name("김선생").build();
    }

    private ConductRequestCreateRequest createRequest() {
      return new ConductRequestCreateRequest(
          studentUserId, assigneeUserId, categoryId, "3교시 10분 지각");
    }

    @Test
    @DisplayName("정상 요청 시 PENDING 상태의 ConductRequest를 저장하고 응답 DTO를 반환한다")
    void createsRequestAndReturnsPendingStatus() {
      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category()));
      given(userRepository.findById(studentUserId)).willReturn(Optional.of(student()));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId))
          .willReturn(List.of("STUDENT"));
      given(userRepository.findById(assigneeUserId)).willReturn(Optional.of(assignee()));
      given(userRoleRepository.findRoleCodesByUserId(assigneeUserId))
          .willReturn(List.of("TEACHER"));
      given(userRepository.findById(requesterUserId)).willReturn(Optional.of(requester()));

      ConductRequest saved = ConductRequest.builder()
          .id(1L)
          .requester(requester())
          .student(student())
          .assignee(assignee())
          .category(category())
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
      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category()));
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
      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category()));
      given(userRepository.findById(studentUserId)).willReturn(Optional.of(student()));
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
      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category()));
      given(userRepository.findById(studentUserId)).willReturn(Optional.of(student()));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId))
          .willReturn(List.of("STUDENT"));
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
      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category()));
      given(userRepository.findById(studentUserId)).willReturn(Optional.of(student()));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId))
          .willReturn(List.of("STUDENT"));
      given(userRepository.findById(assigneeUserId)).willReturn(Optional.of(assignee()));
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
  @DisplayName("cancelRequest")
  class CancelRequest {

    private final Long requesterUserId = 33L;
    private final Long requestId = 1L;

    private User requester() {
      return User.builder().id(requesterUserId).name("홍선도").build();
    }

    private ConductRequest pendingRequest() {
      return ConductRequest.builder()
          .id(requestId)
          .requester(requester())
          .student(User.builder().id(101L).name("길동이").build())
          .assignee(User.builder().id(42L).name("김선생").build())
          .category(ConductCategory.builder()
              .id(5L).label("지각").type(ConductType.DEMERIT).points(-1).active(true).build())
          .detail("3교시 10분 지각")
          .build();
    }

    @Test
    @DisplayName("PENDING 요청을 취소하면 CANCELED 상태로 전환되고 canceledAt이 세팅된다")
    void cancelsPendingRequest() {
      ConductRequest pending = pendingRequest();
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
      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(pendingRequest()));

      assertThatThrownBy(() -> conductRequestService.cancelRequest(999L, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_CANCEL_FORBIDDEN);
    }

    @Test
    @DisplayName("PENDING이 아닌 상태이면 CONDUCT_011 예외를 던진다")
    void throwsWhenNotPending() {
      ConductRequest approved = pendingRequest();
      approved.setStatus(ConductRequestStatus.APPROVED);
      given(conductRequestRepository.findById(requestId)).willReturn(Optional.of(approved));

      assertThatThrownBy(() -> conductRequestService.cancelRequest(requesterUserId, requestId))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.REQUEST_NOT_CANCELLABLE);
    }
  }
}
