package com.remake.gone.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.auth.dto.PhoneSendCodeRequest;
import com.remake.gone.auth.dto.PhoneSendCodeResponse;
import com.remake.gone.auth.dto.PhoneVerifyCodeRequest;
import com.remake.gone.auth.dto.PhoneVerifyCodeResponse;
import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.auth.utils.SmsSender;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PhoneAuthService}에 대한 단위 테스트.
 *
 * <p>Redis와 SMS 발송을 가짜로 대체해서, 인증번호 발송/확인 로직의 분기만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PhoneAuthServiceTest {

  @Mock
  private RedisRepository redisRepository;

  @Mock
  private SmsSender smsSender;

  @InjectMocks
  private PhoneAuthService phoneAuthService;

  private static final String PHONE_NUMBER = "01099999999";

  @Nested
  @DisplayName("sendVerificationCode")
  class SendVerificationCode {

    @Test
    @DisplayName("쿨다운 중이면 SMS를 보내지 않고 거부한다")
    void throwsWhenCoolingDown() {
      given(redisRepository.saveIfAbsent(RedisKeyType.PHONE_SEND_COOLDOWN, PHONE_NUMBER))
          .willReturn(false);
      given(redisRepository.getExpire(RedisKeyType.PHONE_SEND_COOLDOWN, PHONE_NUMBER))
          .willReturn(17L);

      assertThatThrownBy(() -> phoneAuthService.sendVerificationCode(
          new PhoneSendCodeRequest(PHONE_NUMBER)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.TOO_MANY_SMS_REQUESTS);

      verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("정상 발송하면 Redis에 저장한 코드와 같은 코드로 SMS를 보낸다")
    void sendsCodeAndStoresIt() {
      given(redisRepository.saveIfAbsent(RedisKeyType.PHONE_SEND_COOLDOWN, PHONE_NUMBER))
          .willReturn(true);

      PhoneSendCodeResponse response = phoneAuthService.sendVerificationCode(
          new PhoneSendCodeRequest(PHONE_NUMBER));

      ArgumentCaptor<String> savedCode = ArgumentCaptor.forClass(String.class);
      verify(redisRepository)
          .save(eq(RedisKeyType.PHONE_VERIFICATION), eq(PHONE_NUMBER), savedCode.capture());
      assertThat(savedCode.getValue()).matches("\\d{6}"); // 6자리 숫자 문자열이어야 함

      ArgumentCaptor<String> sentMessage = ArgumentCaptor.forClass(String.class);
      verify(smsSender).send(eq(PHONE_NUMBER), sentMessage.capture());
      // Redis에 저장한 코드와 실제로 발송한 메시지 속 코드가 같은지 확인
      assertThat(sentMessage.getValue()).contains(savedCode.getValue());

      assertThat(response.expiresIn()).isEqualTo(RedisKeyType.PHONE_VERIFICATION.ttl().getSeconds());
    }

    @Test
    @DisplayName("SMS 발송이 실패하면 쿨다운을 되돌리고 예외를 그대로 던진다")
    void revertsCooldownWhenSendFails() {
      given(redisRepository.saveIfAbsent(RedisKeyType.PHONE_SEND_COOLDOWN, PHONE_NUMBER))
          .willReturn(true);
      doThrow(new RuntimeException("SMS 발송 실패"))
          .when(smsSender).send(anyString(), anyString());

      assertThatThrownBy(() -> phoneAuthService.sendVerificationCode(
          new PhoneSendCodeRequest(PHONE_NUMBER)))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("SMS 발송 실패");

      verify(redisRepository).delete(RedisKeyType.PHONE_SEND_COOLDOWN, PHONE_NUMBER);
    }
  }

  @Nested
  @DisplayName("verifyCode")
  class VerifyCode {

    private static final String CODE = "123456";

    @Test
    @DisplayName("누적 실패 횟수가 이미 최대치면 코드 확인 없이 거부한다")
    void throwsWhenTooManyAttempts() {
      given(redisRepository.find(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, PHONE_NUMBER, Long.class))
          .willReturn(5L);

      assertThatThrownBy(() -> phoneAuthService.verifyCode(
          new PhoneVerifyCodeRequest(PHONE_NUMBER, CODE)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS);

      verify(redisRepository, never())
          .find(eq(RedisKeyType.PHONE_VERIFICATION), anyString(), eq(String.class));
    }

    @Test
    @DisplayName("저장된 인증번호가 없으면(만료) 거부한다")
    void throwsWhenExpired() {
      given(redisRepository.find(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, PHONE_NUMBER, Long.class))
          .willReturn(null);
      given(redisRepository.find(RedisKeyType.PHONE_VERIFICATION, PHONE_NUMBER, String.class))
          .willReturn(null);

      assertThatThrownBy(() -> phoneAuthService.verifyCode(
          new PhoneVerifyCodeRequest(PHONE_NUMBER, CODE)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.PHONE_CODE_EXPIRED);
    }

    @Test
    @DisplayName("인증번호가 일치하지 않으면 실패 횟수를 늘리고 거부한다")
    void throwsWhenCodeMismatch() {
      given(redisRepository.find(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, PHONE_NUMBER, Long.class))
          .willReturn(2L);
      given(redisRepository.find(RedisKeyType.PHONE_VERIFICATION, PHONE_NUMBER, String.class))
          .willReturn("999999"); // 요청한 CODE("123456")와 다름
      given(redisRepository.increment(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, PHONE_NUMBER))
          .willReturn(3L);

      assertThatThrownBy(() -> phoneAuthService.verifyCode(
          new PhoneVerifyCodeRequest(PHONE_NUMBER, CODE)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_CODE);

      verify(redisRepository).increment(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, PHONE_NUMBER);
    }

    @Test
    @DisplayName("일치하면 인증 관련 키를 지우고 signUpTicket을 발급한다")
    void issuesTicketOnSuccess() {
      given(redisRepository.find(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, PHONE_NUMBER, Long.class))
          .willReturn(null);
      given(redisRepository.find(RedisKeyType.PHONE_VERIFICATION, PHONE_NUMBER, String.class))
          .willReturn(CODE);

      PhoneVerifyCodeResponse response = phoneAuthService.verifyCode(
          new PhoneVerifyCodeRequest(PHONE_NUMBER, CODE));

      assertThat(response.ticket()).isNotBlank();
      assertThat(response.expiresIn()).isEqualTo(RedisKeyType.SIGN_UP_TICKET.ttl().getSeconds());

      verify(redisRepository).delete(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, PHONE_NUMBER);
      verify(redisRepository).delete(RedisKeyType.PHONE_VERIFICATION, PHONE_NUMBER);
      verify(redisRepository)
          .save(eq(RedisKeyType.SIGN_UP_TICKET), eq(response.ticket()), eq(PHONE_NUMBER));
    }
  }
}
