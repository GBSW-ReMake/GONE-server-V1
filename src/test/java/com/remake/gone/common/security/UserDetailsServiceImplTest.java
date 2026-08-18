package com.remake.gone.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.enums.UserStatus;
import com.remake.gone.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * {@link UserDetailsServiceImpl}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserRoleRepository userRoleRepository;

  @InjectMocks
  private UserDetailsServiceImpl userDetailsService;

  @Test
  @DisplayName("로그인 ID로 사용자를 찾으면 역할과 함께 AuthUserDetails로 반환한다")
  void loadsUserByLoginIdWithRoles() {
    User user = User.builder()
        .id(1L)
        .loginId("testuser01")
        .passwordHash("encoded-password")
        .status(UserStatus.ACTIVE)
        .build();
    given(userRepository.findFirstByLoginIdOrPhoneNumber("testuser01", "testuser01"))
        .willReturn(Optional.of(user));
    given(userRoleRepository.findRoleCodesByUserId(1L)).willReturn(List.of("STUDENT"));

    UserDetails result = userDetailsService.loadUserByUsername("testuser01");

    assertThat(result).isInstanceOf(AuthUserDetails.class);
    AuthUserDetails authUserDetails = (AuthUserDetails) result;
    assertThat(authUserDetails.userId()).isEqualTo(1L);
    assertThat(authUserDetails.getUsername()).isEqualTo("testuser01");
    assertThat(authUserDetails.getPassword()).isEqualTo("encoded-password");
    assertThat(authUserDetails.roleCodes()).containsExactly("STUDENT");
  }

  @Test
  @DisplayName("전화번호로 사용자를 찾으면 역할과 함께 AuthUserDetails로 반환한다")
  void loadsUserByPhoneNumberWithRoles() {
    User user = User.builder()
        .id(2L)
        .loginId("testuser02")
        .passwordHash("encoded-password")
        .phoneNumber("01099999999")
        .status(UserStatus.ACTIVE)
        .build();
    given(userRepository.findFirstByLoginIdOrPhoneNumber("01099999999", "01099999999"))
        .willReturn(Optional.of(user));
    given(userRoleRepository.findRoleCodesByUserId(2L)).willReturn(List.of("TEACHER"));

    UserDetails result = userDetailsService.loadUserByUsername("01099999999");

    assertThat(result).isInstanceOf(AuthUserDetails.class);
    AuthUserDetails authUserDetails = (AuthUserDetails) result;
    assertThat(authUserDetails.userId()).isEqualTo(2L);
    assertThat(authUserDetails.getUsername()).isEqualTo("testuser02");
    assertThat(authUserDetails.roleCodes()).containsExactly("TEACHER");
  }

  @Test
  @DisplayName("로그인 ID/전화번호 어느 쪽으로도 못 찾으면 UsernameNotFoundException을 던진다")
  void throwsWhenUserNotFound() {
    given(userRepository.findFirstByLoginIdOrPhoneNumber("nouser", "nouser"))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nouser"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  @DisplayName("자퇴/퇴학 계정이면 계정을 못 찾았을 때와 동일하게 UsernameNotFoundException을 던진다")
  void throwsWhenUserWithdrawn() {
    User user = User.builder()
        .id(3L)
        .loginId("withdrawn01")
        .passwordHash("encoded-password")
        .status(UserStatus.WITHDRAWN)
        .build();
    given(userRepository.findFirstByLoginIdOrPhoneNumber("withdrawn01", "withdrawn01"))
        .willReturn(Optional.of(user));

    assertThatThrownBy(() -> userDetailsService.loadUserByUsername("withdrawn01"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  @DisplayName("졸업 계정이면 정상적으로 AuthUserDetails를 반환한다")
  void loadsUserWhenGraduated() {
    User user = User.builder()
        .id(4L)
        .loginId("graduated01")
        .passwordHash("encoded-password")
        .status(UserStatus.GRADUATED)
        .build();
    given(userRepository.findFirstByLoginIdOrPhoneNumber("graduated01", "graduated01"))
        .willReturn(Optional.of(user));
    given(userRoleRepository.findRoleCodesByUserId(4L)).willReturn(List.of("GRADUATE"));

    UserDetails result = userDetailsService.loadUserByUsername("graduated01");

    assertThat(result).isInstanceOf(AuthUserDetails.class);
    assertThat(((AuthUserDetails) result).userId()).isEqualTo(4L);
  }
}
