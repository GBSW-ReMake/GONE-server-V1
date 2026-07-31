package com.remake.gone.auth.service;

import com.remake.gone.auth.dto.PhoneSendCodeRequest;
import com.remake.gone.auth.dto.PhoneSendCodeResponse;
import com.remake.gone.auth.dto.PhoneSendCooldownResponse;
import com.remake.gone.auth.dto.PhoneVerifyCodeRequest;
import com.remake.gone.auth.dto.PhoneVerifyCodeResponse;
import com.remake.gone.auth.dto.PhoneVerifyFailResponse;
import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.auth.utils.RandomCodeGenerator;
import com.remake.gone.auth.utils.SmsSender;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 휴대폰 인증 관련 비즈니스 로직을 처리하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class PhoneAuthService {

  private final RedisRepository redisRepository;
  private final SmsSender smsSender;

  private static final int MAX_FAIL_COUNT = 5;


  /**
   * 요청한 휴대폰 번호로 인증번호를 생성하여 발송합니다.
   *
   * <p>생성된 인증번호는 {@link RedisKeyType#PHONE_VERIFICATION}에 정의된 TTL(5분) 동안 저장됩니다.
   *
   * @param request 인증번호를 받을 휴대폰 번호 정보
   * @return 인증번호 유효 시간 정보
   */
  public PhoneSendCodeResponse sendVerificationCode(PhoneSendCodeRequest request) {
    String phoneNumber = request.phoneNumber();

    boolean canSend = redisRepository.saveIfAbsent(RedisKeyType.PHONE_SEND_COOLDOWN, phoneNumber);
    if (!canSend) {
      long remainingSeconds = redisRepository.getExpire(RedisKeyType.PHONE_SEND_COOLDOWN, phoneNumber);
      throw new CustomException(
          AuthErrorCode.TOO_MANY_SMS_REQUESTS,
          PhoneSendCooldownResponse.builder()
              .remainingSeconds(remainingSeconds)
              .build());
    }

    String code = RandomCodeGenerator.generate();

    redisRepository.save(RedisKeyType.PHONE_VERIFICATION, phoneNumber, code);

    try {
      // local 전용 로그에 인증번호 뿌리기
      smsSender.send(phoneNumber, "[GONE] 인증번호는 " + code + " 입니다.");
    } catch (RuntimeException e) {
      // 발송 실패 시 쿨다운을 되돌려 사용자가 바로 재시도할 수 있게 한다.
      redisRepository.delete(RedisKeyType.PHONE_SEND_COOLDOWN, phoneNumber);
      throw e;
    }

    return new PhoneSendCodeResponse(RedisKeyType.PHONE_VERIFICATION.ttl().getSeconds());
  }

  /**
   * 휴대폰 인증번호를 검증합니다.
   *
   * <p>인증번호가 만료되었으면 {@link AuthErrorCode#PHONE_CODE_EXPIRED}를,
   * 일치하지 않으면 {@link AuthErrorCode#INVALID_VERIFICATION_CODE}를 던집니다.
   * 검증에 성공하면 {@link RedisKeyType#SIGN_UP_TICKET}에 정의된 TTL(10분) 동안
   * 휴대폰 번호를 값으로 하는 signUpTicket을 발급합니다.
   *
   * @param request 확인할 휴대폰 번호와 사용자가 입력한 인증번호
   * @return 발급된 signUpTicket과 유효 시간
   */
  public PhoneVerifyCodeResponse verifyCode(PhoneVerifyCodeRequest request) {
    String phoneNumber = request.phoneNumber(); // 전화번호
    String code = request.code(); // 인증코드(숫자 6자리)

    Long failCount = redisRepository.find(
        RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, phoneNumber, Long.class);
    if (failCount != null && failCount >= MAX_FAIL_COUNT) {
      throw new CustomException(AuthErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS);
    }

    // Redis에 저장되어있는 인증번호 조회 key -> 접두사 + 전화번호, value -> 인증코드
    String storedCode = redisRepository.find(
        RedisKeyType.PHONE_VERIFICATION, phoneNumber, String.class);

    /**
     * 첫 if문은 인증번호가 만료되었을 때(ttl 5분이 지나서 Redis에서 삭제되었을 때) 발생하는 예외 처리입니다.
     *
     * 두번째 if문은 사용자가 입력한 인증번호와 Redis에 저장된 인증번호가 일치하지 않을 때 발생하는 예외 처리입니다.
     * - 인증번호가 일치하지 않을 경우, Redis에 실패 횟수를 증가시키고,
     *   실패 횟수가 MAX_FAIL_COUNT를 초과하면 TOO_MANY_VERIFICATION_ATTEMPTS 예외를 발생시키고 현재 카운트를 반환합니다.
     */
    if (storedCode == null) {
      throw new CustomException(AuthErrorCode.PHONE_CODE_EXPIRED);
    }
    if (!storedCode.equals(code)) {
      long newFailCount = redisRepository.increment(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, phoneNumber);
      throw new CustomException(
          AuthErrorCode.INVALID_VERIFICATION_CODE,
          PhoneVerifyFailResponse.builder()
              .currentFailCount(newFailCount)
              .maxFailCount(MAX_FAIL_COUNT)
              .build());
    }

    /**
     * 인증 성공 시 Redis에서 인증번호와 실패 횟수 삭제
     */
    redisRepository.delete(RedisKeyType.PHONE_VERIFICATION_FAIL_COUNT, phoneNumber);
    redisRepository.delete(RedisKeyType.PHONE_VERIFICATION, phoneNumber);

    /**
     * [ 티켓의 용도 ]
     *  전화번호 인증이 완료 된 후 다음 로직인 회원가입 (사용자 정보 입력) 진행 시, 해당 전화번호가 인증된 번호인지 확인하기 위해 사용됩니다.
     *
     *  아래 로직은 이렇습니다
     *  - 전화번호 인증 성공 시, 랜덤한 UUID를 생성하여 Redis에 저장합니다. (TTL은 10분)
     *  - 티켓과 TTL을 클라이언트에게 반환합니다.
     */
    UUID ticket = UUID.randomUUID();
    redisRepository.save(RedisKeyType.SIGN_UP_TICKET, ticket.toString(), phoneNumber);

    return new PhoneVerifyCodeResponse(
        ticket.toString(),
        RedisKeyType.SIGN_UP_TICKET.ttl().getSeconds()
    );

  }
}
