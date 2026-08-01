package com.remake.gone.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.auth.dto.SignUpRequest;
import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link AuthService}에 대한 단위 테스트.
 *
 * <p>DB/Redis 대신 Mockito로 만든 가짜 객체를 주입해서, 실제 인프라 없이 signUp()의
 * 분기 로직만 빠르게 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private GbswRepository gbswRepository;

  @Mock
  private RedisRepository redisRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @InjectMocks
  private AuthService authService;

  private static final String TICKET = "valid-ticket";
  private static final String PHONE_NUMBER = "01099999999";

  private SignUpRequest validRequest() {
    return new SignUpRequest("testuser01", "Test1234!", "테스트유저", PHONE_NUMBER, TICKET);
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
    @DisplayName("별명이 이미 사용 중이면 가입을 거부한다")
    void throwsWhenNameDuplicate() {
      Gbsw gbsw = Gbsw.builder().build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      given(userRepository.existsByName("테스트유저")).willReturn(true);

      assertThatThrownBy(() -> authService.signUp(validRequest()))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(UserErrorCode.NAME_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("모든 검증을 통과하면 계정을 저장하고 signUpTicket을 삭제한다")
    void savesUserAndDeletesTicketOnSuccess() {
      Gbsw gbsw = Gbsw.builder().build();
      given(redisRepository.find(RedisKeyType.SIGN_UP_TICKET, TICKET, String.class))
          .willReturn(PHONE_NUMBER);
      given(gbswRepository.findByPhoneNumber(PHONE_NUMBER)).willReturn(Optional.of(gbsw));
      given(userRepository.existsByGbsw(gbsw)).willReturn(false);
      given(userRepository.existsByLoginId("testuser01")).willReturn(false);
      given(userRepository.existsByName("테스트유저")).willReturn(false);
      given(passwordEncoder.encode("Test1234!")).willReturn("encoded-password");

      authService.signUp(validRequest());

      // save에 넘어간 User가 요청 내용과 정확히 일치하는지까지 검증
      verify(userRepository).save(argThat(user ->
          user.getLoginId().equals("testuser01")
              && user.getPasswordHash().equals("encoded-password")
              && user.getName().equals("테스트유저")
              && user.getPhoneNumber().equals(PHONE_NUMBER)
              && user.getGbsw() == gbsw
      ));
      verify(redisRepository).delete(RedisKeyType.SIGN_UP_TICKET, TICKET);
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
}
