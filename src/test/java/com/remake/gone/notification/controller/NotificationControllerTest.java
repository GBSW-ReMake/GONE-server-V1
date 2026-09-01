package com.remake.gone.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.notification.dto.NotificationResponse;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.service.NotificationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link NotificationController}에 대한 웹 계층(슬라이스) 테스트.
 */
@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

  @MockitoBean
  private NotificationService notificationService;

  @Test
  @DisplayName("인증된 사용자의 userId와 페이지 조건을 서비스에 전달한다")
  void callsServiceWithAuthenticatedUserAndPageRequest() {
    UserPrincipal principal = new UserPrincipal(1L);
    PageResponse<NotificationResponse> expected = new PageResponse<>(
        List.of(new NotificationResponse(
            1L, "알림", "내용", NotificationType.OUTING, false,
            LocalDateTime.of(2026, 8, 31, 9, 0))),
        1, 10, 1, 1, false);
    given(notificationService.getNotifications(1L, 1, 10)).willReturn(expected);
    NotificationController controller = new NotificationController(notificationService);

    ApiResponse<PageResponse<NotificationResponse>> response =
        controller.getNotifications(principal, 1, 10);

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo(expected);
    verify(notificationService).getNotifications(1L, 1, 10);
  }
}
