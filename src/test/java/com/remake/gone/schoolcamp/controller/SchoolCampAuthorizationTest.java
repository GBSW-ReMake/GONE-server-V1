package com.remake.gone.schoolcamp.controller;

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
 * {@code POST /api/v1/school-camps}의 인가(@PreAuthorize) 통합 테스트.
 *
 * <p>{@code SchoolCampControllerTest}의 {@code @WebMvcTest(addFilters = false)} 슬라이스는
 * Spring Security 필터 체인이 MockMvc에 붙지 않아 인가 자체를 검증할 수 없다(관례는
 * {@code OutingApproveAuthorizationTest} 등 outing 도메인의 {@code *AuthorizationTest}
 * 참고). 이 테스트는 실제 필터 체인(+ {@code @EnableMethodSecurity})을 통해 STUDENT 역할로는
 * 이 엔드포인트에 접근할 수 없는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SchoolCampAuthorizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(post("/api/v1/school-camps")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"campDates\": [\"20260406\"]}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("STUDENT 역할로 요청하면 403을 반환한다(@EnableMethodSecurity가 실제로 동작함)")
  void returns403ForStudentRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(post("/api/v1/school-camps")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"campDates\": [\"20260406\"]}"))
        .andExpect(status().isForbidden());
  }
}
