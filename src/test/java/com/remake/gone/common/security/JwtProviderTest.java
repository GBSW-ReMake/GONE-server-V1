package com.remake.gone.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link JwtProvider}에 대한 단위 테스트.
 *
 * <p>실제 비밀키로 발급/검증을 왕복시켜 토큰 타입 구분, 역할(role) 클레임, 검증 실패 케이스를
 * 확인한다.
 */
class JwtProviderTest {

  private static final Long USER_ID = 1L;
  private static final Set<String> ROLES = Set.of("STUDENT", "DISCIPLINE");

  private JwtProvider jwtProvider;

  @BeforeEach
  void setUp() {
    JwtProperties properties = new JwtProperties(
        "test-secret-key-for-jwt-provider-unit-test-32bytes+", 1_800_000L, 1_209_600_000L);
    jwtProvider = new JwtProvider(properties);
  }

  @Nested
  @DisplayName("createAccessToken / parseAccessToken")
  class AccessToken {

    @Test
    @DisplayName("발급한 Access Token에서 같은 userId와 역할 목록을 꺼낼 수 있다")
    void roundTrips() {
      String token = jwtProvider.createAccessToken(USER_ID, ROLES);

      JwtProvider.AccessTokenClaims claims = jwtProvider.parseAccessToken(token);

      assertThat(claims.userId()).isEqualTo(USER_ID);
      assertThat(claims.roles()).containsExactlyInAnyOrder("STUDENT", "DISCIPLINE");
    }

    @Test
    @DisplayName("역할이 없어도 빈 목록으로 정상 발급/파싱된다")
    void roundTripsWithNoRoles() {
      String token = jwtProvider.createAccessToken(USER_ID, Set.of());

      JwtProvider.AccessTokenClaims claims = jwtProvider.parseAccessToken(token);

      assertThat(claims.roles()).isEmpty();
    }

    @Test
    @DisplayName("Refresh Token을 Access Token 자리에 쓰면 거부한다")
    void rejectsRefreshTokenAsAccessToken() {
      String refreshToken = jwtProvider.createRefreshToken(USER_ID);

      assertThatThrownBy(() -> jwtProvider.parseAccessToken(refreshToken))
          .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("서명이 다른 키로 발급된 토큰은 거부한다")
    void rejectsTokenSignedWithDifferentKey() {
      JwtProvider otherProvider = new JwtProvider(
          new JwtProperties("different-secret-key-for-forged-token-test-32b", 1_800_000L,
              1_209_600_000L));
      String forgedToken = otherProvider.createAccessToken(USER_ID, ROLES);

      assertThatThrownBy(() -> jwtProvider.parseAccessToken(forgedToken))
          .isInstanceOf(JwtException.class);
    }
  }

  @Nested
  @DisplayName("createRefreshToken / getUserIdFromRefreshToken")
  class RefreshToken {

    @Test
    @DisplayName("발급한 Refresh Token에서 같은 userId를 꺼낼 수 있다")
    void roundTrips() {
      String token = jwtProvider.createRefreshToken(USER_ID);

      assertThat(jwtProvider.getUserIdFromRefreshToken(token)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("Access Token을 Refresh Token 자리에 쓰면 거부한다")
    void rejectsAccessTokenAsRefreshToken() {
      String accessToken = jwtProvider.createAccessToken(USER_ID, ROLES);

      assertThatThrownBy(() -> jwtProvider.getUserIdFromRefreshToken(accessToken))
          .isInstanceOf(JwtException.class);
    }
  }
}
