package com.remake.gone.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.notification.dto.NotificationResponse;
import com.remake.gone.notification.entity.Notification;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.repository.NotificationRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
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
  private UserRepository userRepository;

  @InjectMocks
  private NotificationService notificationService;

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
}
