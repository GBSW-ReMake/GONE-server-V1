package com.remake.gone.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.remake.gone.auth.dto.LoginRequest;
import com.remake.gone.auth.dto.ReissueRequest;
import com.remake.gone.auth.dto.SignUpRequest;
import com.remake.gone.auth.dto.TokenResponse;
import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.common.security.AuthUserDetails;
import com.remake.gone.common.security.JwtProperties;
import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.role.entity.Role;
import com.remake.gone.role.repository.RoleRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AuthService}에 대한 단위 테스트.
 *
 * <p>DB/Redis/AuthenticationManager 대신 Mockito로 만든 가짜 객체를 주입해서, 실제 인프라 없이
 * 분기 로직만 빠르게 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private GbswRepository gbswRepository;

  @Mock
  private RoleRepository roleRepository;

  @Mock
  private UserRoleRepository userRoleRepository;

  @Mock
  private RedisRepository redisRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private JwtProperties jwtProperties;

  @Mock
  private AuthenticationManager authenticationManager;

  @InjectMocks
  private AuthService authService;

  private static final String TICKET = "valid-ticket";
  private static final String PHONE_NUMBER = "01099999999";
  private static final Long USER_ID = 1L;

  private SignUpRequest validRequest() {
    return new SignUpRequest("testuser01", "Test1234!", PHONE_NUMBER, TICKET);
  }

  private void stubTokenIssuance() {
    given(jwtProvider.createAccessToken(any(), any())).willReturn("access-token");
    given(jwtProvider.createRefreshToken(any())).willReturn("refresh-token");
    given(jwtProperties.accessTokenExpiration()).willReturn(1_800_000L);
  }

  @Nested
  @DisplayName("signUp")
  class SignUp {

    @Test
    @DisplayName("ticket이 Redis에 없으면(만료/위조) 가입을 거부한다")
    void throwsWhenTicketMissing() {
      // given: Redis에 이 ticket으로 저장된 값이 없다고 가정
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(null);

      // when & then: 예외가 던져지고, 에러코드가 정확히 일치해야 한다
      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.INVALID_OR_EXPIRED_TICKET);

      // 실패했으니 저장까지 가면 안 된다(부작용 없음을 확인)
      verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("ticket에 저장된 전화번호와 요청 전화번호가 다르면 가입을 거부한다")
    void throwsWhenPhoneNumberMismatch() {
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn("01011112222"); // 요청(01099999999)과 다른 번호

      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.PHONE_NUMBER_MISMATCH);
    }

    @Test
    @DisplayName("학적 명단에 없는 전화번호면 가입을 거부한다")
    void throwsWhenPhoneNumberNotInGbsw() {
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.empty());

      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(GbswErrorCode.NOT_REGISTERED_PHONE_NUMBER);
    }

    @Test
    @DisplayName("이미 그 학적에 연결된 계정이 있으면 가입을 거부한다")
    void throwsWhenAlreadyRegistered() {
      Gbsw gbsw = Gbsw.builder().build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(true);

      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(UserErrorCode.ALREADY_REGISTERED);
    }

    @Test
    @DisplayName("로그인 ID가 이미 사용 중이면 가입을 거부한다")
    void throwsWhenLoginIdDuplicate() {
      Gbsw gbsw = Gbsw.builder().build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(true);

      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(UserErrorCode.LOGIN_ID_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("모든 검증을 통과하면 계정을 저장하고 signUpTicket을 삭제하며 기본 역할을 부여하고 토큰을 발급한다")
    void savesUserAndDeletesTicketOnSuccess() {
      Gbsw gbsw = Gbsw.builder()
          .type(GbswType.STUDENT).name("정문경").grade(3).classNo(1).number(18).build();
      Role studentRole = Role.builder().code("STUDENT").name("학생").build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      given(passwordEncoder.encode("Test1234!")).willReturn("encoded-password");
      given(roleRepository.findByCode("STUDENT")).willReturn(Optional.of(studentRole));
      stubTokenIssuance();

      TokenResponse response = authService.signUp(validRequest());

      // save에 넘어간 User가 요청 내용과 학번 기반 기본 닉네임을 정확히 담고 있는지 검증
      verify(userRepository).save(argThat(user ->
          user.getLoginId().equals("testuser01")
              && user.getPasswordHash().equals("encoded-password")
              && user.getName().equals("3118정문경")
              && user.getPhoneNumber().equals(PHONE_NUMBER)
              && user.getGbsw() == gbsw
      ));
      verify(userRoleRepository).save(argThat(userRole ->
          userRole.getRole() == studentRole
      ));
      verify(redisRepository).delete(RedisKeyType.SIGN_UP_TICKET, TICKET);
      assertThat(response.accessToken()).isEqualTo("access-token");
      assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("번호가 한 자리면 0을 채워 학번을 4자리로 맞춘다")
    void padsSingleDigitNumberInStudentDefaultName() {
      Gbsw gbsw = Gbsw.builder()
          .type(GbswType.STUDENT).name("정문경").grade(3).classNo(1).number(5).build();
      Role studentRole = Role.builder().code("STUDENT").name("학생").build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      given(passwordEncoder.encode("Test1234!")).willReturn("encoded-password");
      given(roleRepository.findByCode("STUDENT")).willReturn(Optional.of(studentRole));
      stubTokenIssuance();

      authService.signUp(validRequest());

      verify(userRepository).save(argThat(user -> user.getName().equals("3105정문경")));
    }

    @Test
    @DisplayName("학생 명단인데 학번 정보(학년/반/번호)가 없으면 예외를 던진다")
    void throwsWhenStudentGbswMissingClassInfo() {
      Gbsw gbsw = Gbsw.builder().type(GbswType.STUDENT).name("정문경").build(); // grade 등 누락
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);

      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(IllegalStateException.class);

      verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("교사는 T-랜덤코드-실명 형식의 기본 닉네임을 부여받는다")
    void generatesTeacherDefaultNameWithRandomSuffix() {
      Gbsw gbsw = Gbsw.builder().type(GbswType.TEACHER).name("김선생").build();
      Role teacherRole = Role.builder().code("TEACHER").name("선생님").build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      given(userRepository.existsByName(any())).willReturn(false);
      given(passwordEncoder.encode("Test1234!")).willReturn("encoded-password");
      given(roleRepository.findByCode("TEACHER")).willReturn(Optional.of(teacherRole));
      stubTokenIssuance();

      authService.signUp(validRequest());

      verify(userRepository).save(argThat(user -> user.getName().matches("^T-[0-9a-f]{3}-김선생$")));
    }

    @Test
    @DisplayName("교사 기본 닉네임이 동명이인과 겹치면 랜덤코드를 다시 뽑는다")
    void regeneratesTeacherDefaultNameOnCollision() {
      Gbsw gbsw = Gbsw.builder().type(GbswType.TEACHER).name("김선생").build();
      Role teacherRole = Role.builder().code("TEACHER").name("선생님").build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      // 첫 번째로 뽑은 랜덤코드는 이미 존재(충돌), 두 번째는 사용 가능
      given(userRepository.existsByName(any())).willReturn(true, false);
      given(passwordEncoder.encode("Test1234!")).willReturn("encoded-password");
      given(roleRepository.findByCode("TEACHER")).willReturn(Optional.of(teacherRole));
      stubTokenIssuance();

      authService.signUp(validRequest());

      verify(userRepository, times(2)).existsByName(any());
      verify(userRepository).save(argThat(user -> user.getName().matches("^T-[0-9a-f]{3}-김선생$")));
    }

    @Test
    @DisplayName("Gbsw.type(STUDENT/TEACHER) 외의 role은 회원가입 시 절대 부여하지 않는다")
    void neverAssignsRolesOtherThanDefaultOnSignUp() {
      Gbsw gbsw = Gbsw.builder().type(GbswType.TEACHER).name("김선생").build();
      Role teacherRole = Role.builder().code("TEACHER").name("선생님").build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      given(userRepository.existsByName(any())).willReturn(false);
      given(passwordEncoder.encode("Test1234!")).willReturn("encoded-password");
      given(roleRepository.findByCode("TEACHER")).willReturn(Optional.of(teacherRole));
      stubTokenIssuance();

      authService.signUp(validRequest());

      // Gbsw.type에 대응하는 role만 딱 한 번 조회/부여되고, 학생회 등 다른 role은 건드리지 않는다.
      verify(roleRepository, times(1)).findByCode(any());
      verify(userRoleRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("기본 역할 시드 데이터가 없으면 예외를 던진다")
    void throwsWhenDefaultRoleSeedMissing() {
      Gbsw gbsw = Gbsw.builder()
          .type(GbswType.STUDENT).name("정문경").grade(3).classNo(1).number(18).build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      given(passwordEncoder.encode("Test1234!")).willReturn("encoded-password");
      given(roleRepository.findByCode("STUDENT")).willReturn(Optional.empty());

      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("isLoginIdAvailable / isNameAvailable")
  class AvailabilityCheck {

    @Test
    @DisplayName("이미 존재하는 로그인 ID면 false를 반환한다")
    void loginIdNotAvailable() {
      given(userRepository.existsByLoginId("taken")).willReturn(true);

      assertThat(authService.isLoginIdAvailable("taken")).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 로그인 ID면 true를 반환한다")
    void loginIdAvailable() {
      given(userRepository.existsByLoginId("free")).willReturn(false);

      assertThat(authService.isLoginIdAvailable("free")).isTrue();
    }

    @Test
    @DisplayName("이미 존재하는 별명이면 false를 반환한다")
    void nameNotAvailable() {
      given(userRepository.existsByName("taken")).willReturn(true);

      assertThat(authService.isNameAvailable("taken")).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 별명이면 true를 반환한다")
    void nameAvailable() {
      given(userRepository.existsByName("free")).willReturn(false);

      assertThat(authService.isNameAvailable("free")).isTrue();
    }
  }

  @Nested
  @DisplayName("login")
  class Login {

    @Test
    @DisplayName("자격증명이 유효하지 않으면 거부한다")
    void throwsWhenAuthenticationFails() {
      given(authenticationManager.authenticate(any()))
          .willThrow(new BadCredentialsException("bad credentials"));

      assertThatThrownBy(() -> authService.login(new LoginRequest("testuser01", "wrong-password")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

      // 자격증명 검증에 실패했으니 토큰 발급까지 가면 안 된다(부작용 없음을 확인).
      verify(jwtProvider, never()).createAccessToken(any(), any());
    }

    @Test
    @DisplayName("자격증명이 유효하면 principal의 역할로 토큰을 발급하고 Refresh Token을 Redis에 저장한다")
    void issuesTokensOnSuccess() {
      AuthUserDetails userDetails =
          new AuthUserDetails(USER_ID, "testuser01", "encoded-password", Set.of("STUDENT"));
      Authentication authentication =
          new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
      given(authenticationManager.authenticate(any())).willReturn(authentication);
      given(jwtProvider.createAccessToken(USER_ID, Set.of("STUDENT"))).willReturn("access-token");
      given(jwtProvider.createRefreshToken(USER_ID)).willReturn("refresh-token");
      given(jwtProperties.accessTokenExpiration()).willReturn(1_800_000L);

      TokenResponse response = authService.login(new LoginRequest("testuser01", "Test1234!"));

      assertThat(response.accessToken()).isEqualTo("access-token");
      assertThat(response.refreshToken()).isEqualTo("refresh-token");
      assertThat(response.accessTokenExpiresIn()).isEqualTo(1_800L);
      verify(redisRepository)
          .save(RedisKeyType.REFRESH_TOKEN, String.valueOf(USER_ID), "refresh-token");
    }
  }

  @Nested
  @DisplayName("reissue")
  class Reissue {

    private static final String REFRESH_TOKEN = "old-refresh-token";

    @Test
    @DisplayName("Refresh Token 서명이 무효/만료됐으면 거부한다")
    void throwsWhenTokenInvalid() {
      willThrow(new JwtException("invalid"))
          .given(jwtProvider).getUserIdFromRefreshToken(REFRESH_TOKEN);

      assertThatThrownBy(() -> authService.reissue(new ReissueRequest(REFRESH_TOKEN)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("Redis에 저장된 Refresh Token이 없으면(만료/로그아웃) 거부한다")
    void throwsWhenNotFoundInRedis() {
      given(jwtProvider.getUserIdFromRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
      given(redisRepository.find(RedisKeyType.REFRESH_TOKEN, String.valueOf(USER_ID), String.class))
          .willReturn(null);

      assertThatThrownBy(() -> authService.reissue(new ReissueRequest(REFRESH_TOKEN)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("Redis에 저장된 값과 다르면(이미 재발급에 쓰여 폐기된 토큰 재사용) 거부한다")
    void throwsWhenTokenReused() {
      given(jwtProvider.getUserIdFromRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
      given(redisRepository.find(RedisKeyType.REFRESH_TOKEN, String.valueOf(USER_ID), String.class))
          .willReturn("newer-refresh-token-issued-already");

      assertThatThrownBy(() -> authService.reissue(new ReissueRequest(REFRESH_TOKEN)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("검증을 통과하면 최신 역할로 새 토큰을 발급하고 Redis 값을 교체(rotation)한다")
    void rotatesTokensOnSuccess() {
      given(jwtProvider.getUserIdFromRefreshToken(REFRESH_TOKEN)).willReturn(USER_ID);
      given(redisRepository.find(RedisKeyType.REFRESH_TOKEN, String.valueOf(USER_ID), String.class))
          .willReturn(REFRESH_TOKEN);
      given(userRoleRepository.findRoleCodesByUserId(USER_ID)).willReturn(List.of("STUDENT"));
      given(jwtProvider.createAccessToken(USER_ID, Set.of("STUDENT")))
          .willReturn("new-access-token");
      given(jwtProvider.createRefreshToken(USER_ID)).willReturn("new-refresh-token");
      given(jwtProperties.accessTokenExpiration()).willReturn(1_800_000L);

      TokenResponse response = authService.reissue(new ReissueRequest(REFRESH_TOKEN));

      assertThat(response.accessToken()).isEqualTo("new-access-token");
      assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
      verify(redisRepository)
          .save(RedisKeyType.REFRESH_TOKEN, String.valueOf(USER_ID), "new-refresh-token");
    }
  }

  @Nested
  @DisplayName("logout")
  class Logout {

    @Test
    @DisplayName("Redis에 저장된 Refresh Token을 삭제한다")
    void deletesRefreshToken() {
      authService.logout(USER_ID);

      verify(redisRepository).delete(RedisKeyType.REFRESH_TOKEN, String.valueOf(USER_ID));
    }
  }
}
