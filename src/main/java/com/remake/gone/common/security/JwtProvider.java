package com.remake.gone.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;

/**
 * Access/Refresh Token의 발급과 검증을 담당한다.
 *
 * <p>두 토큰 모두 같은 서명 키를 쓰지만, {@code tokenType} 클레임으로 종류를 구분한다.
 * Access Token으로 재발급을 시도하거나 그 반대로 쓰는 것을 막기 위함이다. 역할(role) 정보는
 * Access Token에만 담는다 — Refresh Token에 담으면 역할이 바뀐 뒤에도 재발급 때마다 옛 역할이
 * 계속 실려 나가기 때문이다.
 *
 * <p>{@code @Component}가 아니라 {@code SecurityConfig}의 {@code @Bean} 메서드로 등록한다.
 * {@code @WebMvcTest} 슬라이스는 {@code Filter}/{@code Controller} 등 정해진 역할이 아닌 일반
 * 컴포넌트는 스캔에서 제외하므로, 이 클래스를 직접 스캔에 의존하면 슬라이스 테스트의
 * {@code SecurityConfig}가 이 빈을 찾지 못해 깨진다.
 */
@RequiredArgsConstructor
public class JwtProvider {

  private static final String CLAIM_TOKEN_TYPE = "tokenType";
  private static final String CLAIM_ROLES = "roles";
  private static final String TOKEN_TYPE_ACCESS = "access";
  private static final String TOKEN_TYPE_REFRESH = "refresh";

  private final JwtProperties jwtProperties;

  /**
   * Access Token에서 뽑아낸 사용자 ID와 역할 목록.
   *
   * @param userId 토큰 주체(userId)
   * @param roles  발급 시점에 담긴 역할 코드 목록 (예: {@code ["STUDENT", "DISCIPLINE"]})
   */
  public record AccessTokenClaims(Long userId, Set<String> roles) {}

  /**
   * Access Token을 발급합니다.
   *
   * @param userId    토큰 주체가 될 사용자 ID
   * @param roleCodes 함께 실어 보낼 역할 코드 목록
   * @return 서명된 Access Token 문자열
   */
  public String createAccessToken(Long userId, Set<String> roleCodes) {
    Date now = new Date();
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
        .claim(CLAIM_ROLES, roleCodes)
        .issuedAt(now)
        .expiration(new Date(now.getTime() + jwtProperties.accessTokenExpiration()))
        .signWith(key())
        .compact();
  }

  /**
   * Refresh Token을 발급합니다. 역할 정보는 담지 않는다.
   *
   * @param userId 토큰 주체가 될 사용자 ID
   * @return 서명된 Refresh Token 문자열
   */
  public String createRefreshToken(Long userId) {
    Date now = new Date();
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
        .issuedAt(now)
        .expiration(new Date(now.getTime() + jwtProperties.refreshTokenExpiration()))
        .signWith(key())
        .compact();
  }

  /**
   * Access Token을 검증하고 사용자 ID와 역할 목록을 꺼냅니다.
   *
   * @param token 검증할 Access Token
   * @return 사용자 ID와 역할 목록
   * @throws JwtException             서명이 유효하지 않거나 만료된 경우, 또는 Refresh Token이 들어온 경우
   * @throws IllegalArgumentException 토큰이 비어있는 경우
   */
  public AccessTokenClaims parseAccessToken(String token) {
    Claims claims = claims(token, TOKEN_TYPE_ACCESS);
    Long userId = Long.valueOf(claims.getSubject());
    List<?> rawRoles = claims.get(CLAIM_ROLES, List.class);
    Set<String> roles = rawRoles == null
        ? Set.of()
        : rawRoles.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    return new AccessTokenClaims(userId, roles);
  }

  /**
   * Refresh Token을 검증하고 사용자 ID를 꺼냅니다.
   *
   * @param token 검증할 Refresh Token
   * @return 토큰 주체(userId)
   * @throws JwtException             서명이 유효하지 않거나 만료된 경우, 또는 Access Token이 들어온 경우
   * @throws IllegalArgumentException 토큰이 비어있는 경우
   */
  public Long getUserIdFromRefreshToken(String token) {
    return Long.valueOf(claims(token, TOKEN_TYPE_REFRESH).getSubject());
  }

  private Claims claims(String token, String expectedTokenType) {
    Claims claims = Jwts.parser()
        .verifyWith(key())
        .build()
        .parseSignedClaims(token)
        .getPayload();

    // Access Token을 재발급에, Refresh Token을 인증에 쓰는 등 용도 밖 사용을 막는다.
    if (!expectedTokenType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
      throw new JwtException("expected token type " + expectedTokenType + " but was different");
    }

    return claims;
  }

  private SecretKey key() {
    return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
  }
}
