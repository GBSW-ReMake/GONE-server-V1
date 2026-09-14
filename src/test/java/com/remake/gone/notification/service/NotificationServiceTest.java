package com.remake.gone.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.notification.dto.NotificationResponse;
import com.remake.gone.notification.dto.UnreadNotificationCountResponse;
import com.remake.gone.notification.entity.DeviceToken;
import com.remake.gone.notification.entity.Notification;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.exception.NotificationErrorCode;
import com.remake.gone.notification.repository.DeviceTokenRepository;
import com.remake.gone.notification.repository.NotificationRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * {@link NotificationService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  private static final Long USER_ID = 1L;

  @Mock
  private NotificationRepository notificationRepository;

  @Mock
  private DeviceTokenRepository deviceTokenRepository;

  @Mock
  private UserRepository userRepository;

  @InjectMocks
  private NotificationService notificationService;

  @Nested
  @DisplayName("registerDeviceToken")
  class RegisterDeviceToken {

    @Test
    @DisplayName("등록된 토큰이 없으면 현재 사용자 토큰을 새로 저장한다")
    void savesNewDeviceToken() {
      User user = User.builder().id(USER_ID).build();
      given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
      given(deviceTokenRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

      notificationService.registerDeviceToken(USER_ID, "new-fcm-token");

      verify(deviceTokenRepository).save(argThat(deviceToken ->
          deviceToken.getUser().getId().equals(USER_ID)
              && deviceToken.getFcmToken().equals("new-fcm-token")));
    }

    @Test
    @DisplayName("등록된 토큰이 있으면 기존 엔티티의 토큰과 갱신 시각을 변경한다")
    void updatesExistingDeviceToken() {
      LocalDateTime previousUpdatedAt = LocalDateTime.of(2026, 9, 1, 9, 0);
      DeviceToken deviceToken = DeviceToken.builder()
          .id(10L)
          .user(User.builder().id(USER_ID).build())
          .fcmToken("old-fcm-token")
          .updatedAt(previousUpdatedAt)
          .build();
      given(userRepository.findByIdForUpdate(USER_ID))
          .willReturn(Optional.of(User.builder().id(USER_ID).build()));
      given(deviceTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(deviceToken));

      notificationService.registerDeviceToken(USER_ID, "new-fcm-token");

      assertThat(deviceToken.getFcmToken()).isEqualTo("new-fcm-token");
      assertThat(deviceToken.getUpdatedAt()).isEqualTo(previousUpdatedAt);
      verify(deviceTokenRepository).findByUserId(USER_ID);
      verifyNoMoreInteractions(deviceTokenRepository);
      verify(userRepository).findByIdForUpdate(USER_ID);
    }

    @Test
    @DisplayName("Access Token의 사용자가 존재하지 않으면 401 예외를 던진다")
    void rejectsMissingAuthenticatedUser() {
      given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() ->
          notificationService.registerDeviceToken(USER_ID, "new-fcm-token"))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(CommonErrorCode.UNAUTHORIZED);

      verifyNoInteractions(deviceTokenRepository);
    }
  }

  @Nested
  @DisplayName("deleteDeviceToken")
  class DeleteDeviceToken {

    @Test
    @DisplayName("현재 사용자의 디바이스 토큰을 삭제한다")
    void deletesCurrentUsersDeviceToken() {
      given(userRepository.findByIdForUpdate(USER_ID))
          .willReturn(Optional.of(User.builder().id(USER_ID).build()));

      notificationService.deleteDeviceToken(USER_ID);

      verify(deviceTokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("Access Token의 사용자가 존재하지 않으면 토큰을 삭제하지 않는다")
    void doesNotDeleteTokenForMissingAuthenticatedUser() {
      given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() -> notificationService.deleteDeviceToken(USER_ID))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(CommonErrorCode.UNAUTHORIZED);

      verifyNoInteractions(deviceTokenRepository);
    }
  }

  @Nested
  @DisplayName("getNotifications")
  class GetNotifications {

    @Test
    @DisplayName("사용자 ID와 페이지 조건으로 최신순 알림을 조회한다")
    void returnsNotificationsPage() {
      Notification notification = mock(Notification.class);
      given(notification.getId()).willReturn(10L);
      given(notification.getTitle()).willReturn("외출증 승인");
      given(notification.getBody()).willReturn("외출증이 승인되었습니다.");
      given(notification.getType()).willReturn(NotificationType.OUTING);
      given(notification.isRead()).willReturn(false);
      given(notification.getCreatedAt()).willReturn(LocalDateTime.of(2026, 8, 31, 9, 0));
      PageRequest pageable = PageRequest.of(0, 20, Sort.by(
          Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
      given(notificationRepository.findByUserId(1L, pageable))
          .willReturn(new PageImpl<>(List.of(notification), pageable, 1));

      PageResponse<NotificationResponse> response = notificationService.getNotifications(1L, 0, 20);

      assertThat(response.content()).containsExactly(new NotificationResponse(
          10L, "외출증 승인", "외출증이 승인되었습니다.", NotificationType.OUTING, false,
          LocalDateTime.of(2026, 8, 31, 9, 0)));
      assertThat(response.page()).isZero();
      assertThat(response.size()).isEqualTo(20);
      assertThat(response.totalElements()).isOne();
      assertThat(response.hasNext()).isFalse();
      verify(notificationRepository).findByUserId(1L, pageable);
    }

    @Test
    @DisplayName("페이지 번호가 음수면 NOTIFICATION_003 예외를 던진다")
    void rejectsNegativePage() {
      assertThatThrownBy(() -> notificationService.getNotifications(1L, -1, 20))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode.code")
          .isEqualTo("NOTIFICATION_003");
    }

    @Test
    @DisplayName("페이지 크기가 100을 초과하면 NOTIFICATION_003 예외를 던진다")
    void rejectsOversizedPage() {
      assertThatThrownBy(() -> notificationService.getNotifications(1L, 0, 101))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode.code")
          .isEqualTo("NOTIFICATION_003");
    }

    @Test
    @DisplayName("페이지 크기가 1보다 작으면 NOTIFICATION_003 예외를 던진다")
    void rejectsEmptyPage() {
      assertThatThrownBy(() -> notificationService.getNotifications(1L, 0, 0))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode.code")
          .isEqualTo("NOTIFICATION_003");
    }
  }

  @Nested
  @DisplayName("markAsRead")
  class MarkAsRead {

    @Test
    @DisplayName("본인 알림을 읽음 처리한다")
    void marksOwnNotificationAsRead() {
      Notification notification = notification(USER_ID, false);
      given(notificationRepository.findById(10L)).willReturn(Optional.of(notification));

      notificationService.markAsRead(USER_ID, 10L);

      assertThat(notification.isRead()).isTrue();
      verify(notificationRepository).findById(10L);
    }

    @Test
    @DisplayName("이미 읽은 본인 알림도 성공으로 처리한다")
    void keepsAlreadyReadNotificationAsRead() {
      Notification notification = notification(USER_ID, true);
      given(notificationRepository.findById(10L)).willReturn(Optional.of(notification));

      notificationService.markAsRead(USER_ID, 10L);

      assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 알림이면 NOTIFICATION_001 예외를 던진다")
    void rejectsMissingNotification() {
      given(notificationRepository.findById(10L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> notificationService.markAsRead(USER_ID, 10L))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 알림이면 NOTIFICATION_002 예외를 던진다")
    void rejectsOtherUsersNotification() {
      Notification notification = notification(2L, false);
      given(notificationRepository.findById(10L)).willReturn(Optional.of(notification));

      assertThatThrownBy(() -> notificationService.markAsRead(USER_ID, 10L))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
      assertThat(notification.isRead()).isFalse();
    }
  }

  @Nested
  @DisplayName("markAllAsRead")
  class MarkAllAsRead {

    @Test
    @DisplayName("현재 사용자의 읽지 않은 알림을 벌크 읽음 처리하고 처리 건수를 반환한다")
    void marksAllUnreadNotificationsAsRead() {
      given(notificationRepository.markAllAsReadByUserId(USER_ID)).willReturn(2);

      int updatedCount = notificationService.markAllAsRead(USER_ID);

      assertThat(updatedCount).isEqualTo(2);
      verify(notificationRepository).markAllAsReadByUserId(USER_ID);
    }

    @Test
    @DisplayName("읽지 않은 알림이 없으면 처리 건수 0을 반환한다")
    void returnsZeroWhenThereAreNoUnreadNotifications() {
      given(notificationRepository.markAllAsReadByUserId(USER_ID)).willReturn(0);

      int updatedCount = notificationService.markAllAsRead(USER_ID);

      assertThat(updatedCount).isZero();
    }
  }

  @Nested
  @DisplayName("getUnreadCount")
  class GetUnreadCount {

    @Test
    @DisplayName("현재 사용자의 읽지 않은 알림 개수를 응답 DTO로 반환한다")
    void returnsUnreadNotificationCount() {
      given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(3L);

      UnreadNotificationCountResponse response = notificationService.getUnreadCount(USER_ID);

      assertThat(response).isEqualTo(new UnreadNotificationCountResponse(3L));
      verify(notificationRepository).countByUserIdAndIsReadFalse(USER_ID);
    }
  }

  @Nested
  @DisplayName("send")
  class Send {

    @Test
    @DisplayName("전달받은 값 그대로 알림을 저장한다")
    void savesNotificationWithGivenValues() {
      given(userRepository.getReferenceById(USER_ID))
          .willReturn(User.builder().id(USER_ID).build());

      notificationService.send(USER_ID, "외출증이 승인되었습니다", "12:30 외출을 승인했어요.",
          NotificationType.OUTING);

      verify(notificationRepository).save(argThat(notification ->
          notification.getUser().getId().equals(USER_ID)
              && notification.getTitle().equals("외출증이 승인되었습니다")
              && notification.getBody().equals("12:30 외출을 승인했어요.")
              && notification.getType() == NotificationType.OUTING
              && !notification.isRead()));
    }

    @Test
    @DisplayName("type이 null이어도 저장된다")
    void allowsNullType() {
      given(userRepository.getReferenceById(USER_ID))
          .willReturn(User.builder().id(USER_ID).build());

      notificationService.send(USER_ID, "제목", "본문", null);

      verify(notificationRepository).save(argThat(notification -> notification.getType() == null));
    }
  }

  private Notification notification(Long userId, boolean isRead) {
    return Notification.builder()
        .id(10L)
        .user(User.builder().id(userId).build())
        .title("외출증 승인")
        .body("외출증이 승인되었습니다.")
        .isRead(isRead)
        .build();
  }
}
