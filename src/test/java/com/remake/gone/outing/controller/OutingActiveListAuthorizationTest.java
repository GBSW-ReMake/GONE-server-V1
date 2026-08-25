package com.remake.gone.outing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * {@code GET /api/v1/outings/active}의 인가(@PreAuthorize) 통합 테스트(#96).
 *
 * <p>{@code OutingReceivedAuthorizationTest}와 같은 이유로, {@code @WebMvcTest} 슬라이스가 아니라
 * 실제 필터 체인(+ {@code @EnableMethodSecurity})을 통해 검증한다. {@code DISCIPLINE}/
 * {@code TEACHER}/{@code ADMIN}은 통과(200), 그 외 역할은 차단(403)되는지 함께 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingActiveListAuthorizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/outings/active"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("STUDENT 역할로 요청하면 403을 반환한다")
  void returns403ForStudentRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(get("/api/v1/outings/active")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("DISCIPLINE 역할로 요청하면 200을 반환한다")
  void returns200ForDisciplineRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("DISCIPLINE"));

    mockMvc.perform(get("/api/v1/outings/active")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("TEACHER 역할로 요청하면 200을 반환한다")
  void returns200ForTeacherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("TEACHER"));

    mockMvc.perform(get("/api/v1/outings/active")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("ADMIN 역할로 요청하면 200을 반환한다")
  void returns200ForAdminRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(get("/api/v1/outings/active")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }
}
