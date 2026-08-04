package com.remake.gone.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthUserDetails}에 대한 단위 테스트.
 */
class AuthUserDetailsTest {

  @Test
  @DisplayName("getUsername/getPassword는 loginId/passwordHash를 그대로 반환한다")
  void returnsRawFields() {
    AuthUserDetails userDetails =
        new AuthUserDetails(1L, "testuser01", "encoded-password", Set.of("STUDENT"));

    assertThat(userDetails.getUsername()).isEqualTo("testuser01");
    assertThat(userDetails.getPassword()).isEqualTo("encoded-password");
  }

  @Test
  @DisplayName("getAuthorities는 role code를 ROLE_ 접두사 권한으로 변환한다")
  void mapsRoleCodesToRoleAuthorities() {
    AuthUserDetails userDetails =
        new AuthUserDetails(1L, "testuser01", "encoded-password", Set.of("STUDENT", "DISCIPLINE"));

    assertThat(userDetails.getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_DISCIPLINE");
  }

  @Test
  @DisplayName("역할이 없으면 권한도 비어있다")
  void returnsEmptyAuthoritiesWhenNoRoles() {
    AuthUserDetails userDetails =
        new AuthUserDetails(1L, "testuser01", "encoded-password", Set.of());

    assertThat(userDetails.getAuthorities()).isEmpty();
  }
}
