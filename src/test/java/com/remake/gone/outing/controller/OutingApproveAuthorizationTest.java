package com.remake.gone.outing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * {@code PATCH /api/v1/outings/{code}/approve}의 인가(@PreAuthorize) 통합 테스트.
 *
 * <p>{@code OutingControllerTest}의 {@code @WebMvcTest(addFilters = false)} 슬라이스는 Spring
 * Security 필터 체인이 MockMvc에 붙지 않아 인가 자체를 검증할 수 없다. 이 테스트는 실제
 * 필터 체인(+ {@code @EnableMethodSecurity})을 통해 STUDENT 역할로는 이 엔드포인트에 접근할
 * 수 없는지 확인한다 — {@code @EnableMethodSecurity}가 빠지면 {@code @PreAuthorize}가 조용히
 * 무시되어 이 테스트가 실패해야 정상이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingApproveAuthorizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(patch("/api/v1/outings/ANYCODE0001/approve"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("STUDENT 역할로 요청하면 403을 반환한다(@EnableMethodSecurity가 실제로 동작함)")
  void returns403ForStudentRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(patch("/api/v1/outings/ANYCODE0001/approve")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }
}
