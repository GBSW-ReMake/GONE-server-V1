package com.remake.gone.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link JwtAuthenticationFilter}에 대한 단위 테스트.
 *
 * <p>실제 서블릿 필터 체인/Spring Security 없이, {@code doFilterInternal()}을 직접 호출해
 * 헤더 파싱과 {@code SecurityContext} 세팅 분기만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  private static final Long USER_ID = 1L;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private FilterChain filterChain;

  @InjectMocks
  private JwtAuthenticationFilter filter;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Authorization 헤더가 없으면 인증 정보를 세팅하지 않고 그대로 통과시킨다")
  void passesThroughWhenNoAuthorizationHeader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
  }

  @Test
  @DisplayName("Bearer 접두사가 없는 헤더는 토큰이 없는 것으로 취급한다")
  void treatsHeaderWithoutBearerPrefixAsNoToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "some-raw-token-without-bearer-prefix");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
  }

  @Test
  @DisplayName("유효한 Access Token이면 SecurityContext에 UserPrincipal과 역할 권한을 세팅한다")
  void setsAuthenticationWhenTokenValid() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer valid-access-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    given(jwtProvider.parseAccessToken("valid-access-token"))
        .willReturn(new JwtProvider.AccessTokenClaims(USER_ID, Set.of("STUDENT", "DISCIPLINE")));

    filter.doFilterInternal(request, response, filterChain);

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getPrincipal()).isEqualTo(new UserPrincipal(USER_ID));
    assertThat(authentication.getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_DISCIPLINE");
    verify(filterChain).doFilter(request, response);
  }

  @Test
  @DisplayName("토큰 검증에 실패해도 요청을 막지 않고, 인증 정보 없이 통과시킨다")
  void clearsContextAndPassesThroughWhenTokenInvalid() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer forged-or-expired-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    willThrow(new JwtException("invalid signature"))
        .given(jwtProvider).parseAccessToken("forged-or-expired-token");

    filter.doFilterInternal(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
  }
}
