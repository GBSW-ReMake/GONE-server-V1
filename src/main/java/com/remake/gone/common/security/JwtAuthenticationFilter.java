package com.remake.gone.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer {accessToken}} 헤더를 읽어 {@code SecurityContext}에 인증 정보를 채운다.
 *
 * <p>토큰이 없거나 유효하지 않아도 이 필터는 요청을 막지 않는다 — 대부분의 API는 아직
 * {@code permitAll}이므로, 인증이 실제로 필요한 경로인지는 {@code SecurityConfig}의
 * {@code authorizeHttpRequests}가 판단한다. 이 필터는 "토큰이 유효하면 인증 정보를 세팅"까지만
 * 책임진다.
 *
 * <p>{@code @Component}가 아니라 {@code SecurityConfig}의 {@code @Bean} 메서드로 등록한다
 * ({@link JwtProvider} 클래스 주석 참고).
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtProvider jwtProvider;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String token = resolveToken(request);

    if (token != null) {
      try {
        JwtProvider.AccessTokenClaims claims = jwtProvider.parseAccessToken(token);
        UserPrincipal principal = new UserPrincipal(claims.userId());
        List<GrantedAuthority> authorities = claims.roles().stream()
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        Authentication authentication =
            new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (JwtException | IllegalArgumentException e) {
        // 서명 위조/만료/타입 불일치 등 어떤 이유든 인증되지 않은 상태로만 두고 계속 진행한다.
        // 이후 인증이 필요한 경로면 authorizeHttpRequests가 401로 막는다.
        SecurityContextHolder.clearContext();
      }
    }

    filterChain.doFilter(request, response);
  }

  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader(AUTHORIZATION_HEADER);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}
