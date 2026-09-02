package com.remake.gone.notification.controller;

import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
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
 * {@link NotificationController}의 전체 읽음 처리 HTTP·인증 통합 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationReadAllControllerTest {

  private static final Long USER_ID = 1L;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @MockitoBean
  private NotificationService notificationService;

  @Test
  @DisplayName("인증된 사용자가 전체 읽음 처리하면 200을 반환한다")
  void returns200ForAuthenticatedUser() throws Exception {
    willDoNothing().given(notificationService).markAllAsRead(USER_ID);

    mockMvc.perform(patch("/api/v1/notifications/read-all")
            .header("Authorization", "Bearer " + accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(jsonPath("$.message").value("모든 알림을 읽음 처리했습니다."));

    verify(notificationService).markAllAsRead(USER_ID);
  }

  @Test
  @DisplayName("인증 없이 전체 읽음 처리하면 401 COMMON_002를 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(patch("/api/v1/notifications/read-all"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_002"));
  }

  private String accessToken() {
    return jwtProvider.createAccessToken(USER_ID, Set.of("STUDENT"));
  }
}
