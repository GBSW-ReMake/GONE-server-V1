package com.remake.gone.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link RestAuthenticationEntryPoint}에 대한 단위 테스트.
 *
 * <p>인증되지 않은 요청이 다른 API들과 동일한 {@code ApiResponse} 포맷(401, COMMON_002)으로
 * 응답되는지 확인한다.
 */
class RestAuthenticationEntryPointTest {

  private final RestAuthenticationEntryPoint entryPoint =
      new RestAuthenticationEntryPoint(new ObjectMapper());

  @Test
  @DisplayName("401 상태와 COMMON_002 에러코드를 담은 JSON 응답을 작성한다")
  void writesUnauthorizedResponse() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AuthenticationException exception = new AuthenticationException("not authenticated") {};

    entryPoint.commence(request, response, exception);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("\"code\":\"COMMON_002\"");
    assertThat(response.getContentAsString()).contains("\"success\":false");
  }
}
