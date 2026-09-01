package com.remake.gone.notification.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link NotificationController}의 HTTP·인증 통합 테스트.
 *
 * <p>실제 Spring Security 필터 체인과 {@code @AuthenticationPrincipal}을 거쳐, 인증·요청
 * 파라미터 바인딩·예외 응답을 함께 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Test
  @DisplayName("인증된 사용자가 기본 페이지 조건으로 알림 목록을 조회하면 200을 반환한다")
  void returns200ForAuthenticatedUser() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(get("/api/v1/notifications")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20));
  }

  @Test
  @DisplayName("페이지 번호가 음수면 400 NOTIFICATION_003을 반환한다")
  void returns400ForNegativePage() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(get("/api/v1/notifications")
            .param("page", "-1")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_003"));
  }

  @Test
  @DisplayName("페이지 크기가 범위를 벗어나면 400 NOTIFICATION_003을 반환한다")
  void returns400ForInvalidSize() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(get("/api/v1/notifications")
            .param("size", "101")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_003"));
  }

  @Test
  @DisplayName("인증 없이 요청하면 401 COMMON_002를 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/notifications"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_002"));
  }
}
