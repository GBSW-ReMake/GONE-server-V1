package com.remake.gone.notification.controller;

import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.notification.service.NotificationService;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link NotificationController}의 FCM 디바이스 토큰 API HTTP·인증 통합 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationDeviceTokenControllerTest {

  private static final Long USER_ID = 1L;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @MockitoBean
  private NotificationService notificationService;

  @Nested
  @DisplayName("registerDeviceToken")
  class RegisterDeviceToken {

    @Test
    @DisplayName("인증된 앱이 유효한 FCM 토큰을 등록하면 200을 반환한다")
    void returns200ForValidToken() throws Exception {
      willDoNothing().given(notificationService)
          .registerDeviceToken(USER_ID, "valid-fcm-token");

      mockMvc.perform(put("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fcmToken\":\"valid-fcm-token\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.data").doesNotExist())
          .andExpect(jsonPath("$.message").value("디바이스 토큰이 등록되었습니다."));

      verify(notificationService).registerDeviceToken(USER_ID, "valid-fcm-token");
    }

    @Test
    @DisplayName("FCM 토큰이 정확히 255자면 200을 반환한다")
    void returns200ForMaximumLengthToken() throws Exception {
      String maximumLengthToken = "a".repeat(255);

      mockMvc.perform(put("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fcmToken\":\"" + maximumLengthToken + "\"}"))
          .andExpect(status().isOk());

      verify(notificationService).registerDeviceToken(USER_ID, maximumLengthToken);
    }

    @Test
    @DisplayName("FCM 토큰이 공백이면 400 COMMON_001을 반환한다")
    void returns400ForBlankToken() throws Exception {
      mockMvc.perform(put("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fcmToken\":\"   \"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("COMMON_001"));

      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("FCM 토큰 필드가 없으면 400 COMMON_001을 반환한다")
    void returns400ForMissingToken() throws Exception {
      mockMvc.perform(put("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("COMMON_001"));

      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("FCM 토큰이 255자를 초과하면 400 COMMON_001을 반환한다")
    void returns400ForOversizedToken() throws Exception {
      String oversizedToken = "a".repeat(256);

      mockMvc.perform(put("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fcmToken\":\"" + oversizedToken + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("COMMON_001"));

      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("요청 본문 JSON이 올바르지 않으면 400 COMMON_001을 반환한다")
    void returns400ForMalformedJson() throws Exception {
      mockMvc.perform(put("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fcmToken\":"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("COMMON_001"));

      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("요청 본문이 없으면 400 COMMON_001을 반환한다")
    void returns400ForMissingRequestBody() throws Exception {
      mockMvc.perform(put("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken())
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("COMMON_001"));

      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("인증 없이 FCM 토큰을 등록하면 401 COMMON_002를 반환한다")
    void returns401WithoutAuthentication() throws Exception {
      mockMvc.perform(put("/api/v1/notifications/device-token")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fcmToken\":\"valid-fcm-token\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("COMMON_002"));

      verifyNoInteractions(notificationService);
    }
  }

  @Nested
  @DisplayName("deleteDeviceToken")
  class DeleteDeviceToken {

    @Test
    @DisplayName("인증된 사용자의 디바이스 토큰을 삭제하면 200을 반환한다")
    void returns200ForAuthenticatedUser() throws Exception {
      willDoNothing().given(notificationService).deleteDeviceToken(USER_ID);

      mockMvc.perform(delete("/api/v1/notifications/device-token")
              .header("Authorization", "Bearer " + accessToken()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.data").doesNotExist())
          .andExpect(jsonPath("$.message").value("디바이스 토큰이 삭제되었습니다."));

      verify(notificationService).deleteDeviceToken(USER_ID);
    }

    @Test
    @DisplayName("인증 없이 디바이스 토큰을 삭제하면 401 COMMON_002를 반환한다")
    void returns401WithoutAuthentication() throws Exception {
      mockMvc.perform(delete("/api/v1/notifications/device-token"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("COMMON_002"));

      verifyNoInteractions(notificationService);
    }
  }

  private String accessToken() {
    return jwtProvider.createAccessToken(USER_ID, Set.of("STUDENT"));
  }
}
