package com.remake.gone.notification.controller;

import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.notification.exception.NotificationErrorCode;
import com.remake.gone.notification.service.NotificationService;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link NotificationController}의 단건 읽음 처리 HTTP·인증 통합 테스트.
 *
 * <p>실제 Spring Security 필터 체인을 유지하고, 서비스만 대체해 요청 매핑·인증·예외 응답을
 * 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationReadControllerTest {

  private static final Long USER_ID = 1L;
  private static final Long NOTIFICATION_ID = 10L;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @MockitoBean
  private NotificationService notificationService;

  @Test
  @DisplayName("인증된 사용자가 본인 알림을 읽음 처리하면 200을 반환한다")
  void returns200ForAuthenticatedUser() throws Exception {
    String token = accessToken();
    willDoNothing().given(notificationService).markAsRead(USER_ID, NOTIFICATION_ID);

    mockMvc.perform(patch("/api/v1/notifications/{id}/read", NOTIFICATION_ID)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(jsonPath("$.message").value("알림을 읽음 처리했습니다."));

    verify(notificationService).markAsRead(USER_ID, NOTIFICATION_ID);
  }

  @Test
  @DisplayName("존재하지 않는 알림이면 404 NOTIFICATION_001을 반환한다")
  void returns404ForMissingNotification() throws Exception {
    willThrow(new CustomException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
        .given(notificationService).markAsRead(USER_ID, NOTIFICATION_ID);

    mockMvc.perform(patch("/api/v1/notifications/{id}/read", NOTIFICATION_ID)
            .header("Authorization", "Bearer " + accessToken()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_001"));
  }

  @Test
  @DisplayName("다른 사용자의 알림이면 403 NOTIFICATION_002를 반환한다")
  void returns403ForOtherUsersNotification() throws Exception {
    willThrow(new CustomException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED))
        .given(notificationService).markAsRead(USER_ID, NOTIFICATION_ID);

    mockMvc.perform(patch("/api/v1/notifications/{id}/read", NOTIFICATION_ID)
            .header("Authorization", "Bearer " + accessToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_002"));
  }

  @Test
  @DisplayName("인증 없이 요청하면 401 COMMON_002를 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(patch("/api/v1/notifications/{id}/read", NOTIFICATION_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_002"));
  }

  private String accessToken() {
    return jwtProvider.createAccessToken(USER_ID, Set.of("STUDENT"));
  }
}
