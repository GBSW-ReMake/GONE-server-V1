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
 * {@code GET /api/v1/outings/{code}/locations}의 인가 통합 테스트(#97).
 *
 * <p>{@code OutingDetailAuthorizationTest}와 같은 이유로, 이 엔드포인트는 특정 역할이 아니라
 * 인증 여부만 {@code @PreAuthorize("isAuthenticated()")}로 확인하고, 담당 선생님 본인 여부 +
 * {@code DISCIPLINE}/{@code ADMIN} 역할 판단은 서비스 코드가 한다({@code OutingServiceTest}의
 * {@code getOutingLocations}에서 검증). 여기서는 인증 자체가 없을 때 401만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingLocationsAuthorizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/outings/ANYCODE0001/locations"))
        .andExpect(status().isUnauthorized());
  }
}
