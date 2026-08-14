package com.remake.gone.notification.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.remake.gone.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NotificationService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  private static final Long USER_ID = 1L;

  @Mock
  private NotificationRepository notificationRepository;

  @InjectMocks
  private NotificationService notificationService;

  @Nested
  @DisplayName("send")
  class Send {

    @Test
    @DisplayName("전달받은 값 그대로 알림을 저장한다")
    void savesNotificationWithGivenValues() {
      notificationService.send(USER_ID, "외출증이 승인되었습니다", "12:30 외출을 승인했어요.",
          "OUTING_APPROVED");

      verify(notificationRepository).save(argThat(notification ->
          notification.getUser().getId().equals(USER_ID)
              && notification.getTitle().equals("외출증이 승인되었습니다")
              && notification.getBody().equals("12:30 외출을 승인했어요.")
              && notification.getType().equals("OUTING_APPROVED")
              && !notification.isRead()));
    }

    @Test
    @DisplayName("type이 null이어도 저장된다")
    void allowsNullType() {
      notificationService.send(USER_ID, "제목", "본문", null);

      verify(notificationRepository).save(argThat(notification ->
          notification.getType() == null));
    }
  }
}
