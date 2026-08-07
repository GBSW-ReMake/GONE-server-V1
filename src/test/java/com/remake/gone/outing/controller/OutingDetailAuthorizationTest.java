package com.remake.gone.outing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/outings/{code}}의 인가 통합 테스트(#41).
 *
 * <p>이 엔드포인트는 특정 역할이 아니라 인증 여부만 {@code @PreAuthorize("isAuthenticated()")}로
 * 확인하고(같은 컨트롤러의 다른 메서드와 애노테이션 사용 패턴을 맞추기 위함, 기획서 참고),
 * 소유권/세부 역할(DISCIPLINE/ADMIN) 판단은 서비스 코드가 한다({@code OutingServiceTest}의
 * {@code getOutingDetail}에서 검증). 여기서는 인증 자체가 없을 때 401만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingDetailAuthorizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/outings/ANYCODE0001"))
        .andExpect(status().isUnauthorized());
  }
}
