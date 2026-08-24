package com.remake.gone.outing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/outings/{code}/depart}의 인가(@PreAuthorize) 통합 테스트(#43).
 *
 * <p>{@code OutingApproveAuthorizationTest}와 같은 이유로, {@code @WebMvcTest} 슬라이스가 아니라
 * 실제 필터 체인(+ {@code @EnableMethodSecurity})을 통해 TEACHER 역할로는 이 엔드포인트에 접근할
 * 수 없는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingDepartAuthorizationTest {

  private static final String VALID_BODY = "{\"latitude\": 36.1234, \"longitude\": 128.4321}";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(post("/api/v1/outings/ANYCODE0001/depart")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("TEACHER 역할로 요청하면 403을 반환한다(@EnableMethodSecurity가 실제로 동작함)")
  void returns403ForTeacherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("TEACHER"));

    mockMvc.perform(post("/api/v1/outings/ANYCODE0001/depart")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
        .andExpect(status().isForbidden());
  }
}
