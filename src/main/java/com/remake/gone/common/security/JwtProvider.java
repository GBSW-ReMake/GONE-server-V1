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
 * <p>두 토큰은 서로 다른 서명 키를 쓴다(#52) — 한쪽 키가 유출돼도 반대쪽 토큰까지 위조할 수
 * 없도록 피해 범위를 분리한다. {@code tokenType} 클레임도 함께 남겨 종류를 구분하는데, 이제는
 * 서명 검증(다른 키로 서명된 토큰은 이 단계에서부터 실패)이 1차 방어선이고 클레임 검사는
 * 그 위에 남겨두는 안전망이다. 역할(role) 정보는 Access Token에만 담는다 — Refresh Token에
 * 담으면 역할이 바뀐 뒤에도 재발급 때마다 옛 역할이 계속 실려 나가기 때문이다.
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
        .signWith(key(TOKEN_TYPE_ACCESS))
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
        .signWith(key(TOKEN_TYPE_REFRESH))
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
    // 기대하는 토큰 종류의 키로 먼저 검증한다 — 다른 종류로 서명된 토큰(예: Refresh Token
    // 키로 서명된 토큰을 Access Token으로 파싱 시도)은 아래 클레임 검사까지 가지도 못하고
    // 여기서 서명 불일치로 곧바로 실패한다.
    Claims claims = Jwts.parser()
        .verifyWith(key(expectedTokenType))
        .build()
        .parseSignedClaims(token)
        .getPayload();

    // 서명 키가 우연히 같아지는 등의 회귀를 잡는 안전망으로 클레임 검사도 유지한다.
    if (!expectedTokenType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
      throw new JwtException("expected token type " + expectedTokenType + " but was different");
    }

    return claims;
  }

  private SecretKey key(String tokenType) {
    String secret;
    if (TOKEN_TYPE_ACCESS.equals(tokenType)) {
      secret = jwtProperties.accessTokenSecret();
    } else if (TOKEN_TYPE_REFRESH.equals(tokenType)) {
      secret = jwtProperties.refreshTokenSecret();
    } else {
      // 알 수 없는 tokenType이 조용히 refresh 키로 처리되는 걸 막는다(#52 코드 리뷰) —
      // 이 메서드는 클래스 내부의 TOKEN_TYPE_* 상수만 넘겨받아야 하므로, 여기 도달하면
      // 호출부 실수(예: 세 번째 토큰 종류를 상수 재사용 없이 추가)를 뜻한다.
      throw new IllegalArgumentException("unknown token type: " + tokenType);
    }
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }
}
