package com.remake.gone.auth.service;

import com.remake.gone.auth.dto.SignUpRequest;
import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증(Auth) 관련 비즈니스 로직을 처리하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final GbswRepository gbswRepository;
  private final RedisRepository redisRepository;
  private final PasswordEncoder passwordEncoder;

  /**
   * 휴대폰 인증(signUpTicket)을 검증한 뒤, 명단(Gbsw)과 연결된 회원 계정을 생성합니다.
   *
   * @param request 회원가입 요청 정보
   */
  @Transactional
  public void signUp(SignUpRequest request) {
    String phoneNumber = request.phoneNumber();

    String verifiedPhone = redisRepository.find(
        RedisKeyType.SIGN_UP_TICKET, request.ticket(), String.class
    );

    // 전화번호 인증 시간이 지났거나 티켓 오류
    if (verifiedPhone == null) {
      throw new CustomException(AuthErrorCode.INVALID_OR_EXPIRED_TICKET);
    }
    // 전화번호 인증 티켓에 저장된 번호와 요청한 번호가 일치하지 않음
    if (!verifiedPhone.equals(phoneNumber)) {
      throw new CustomException(AuthErrorCode.PHONE_NUMBER_MISMATCH);
    }

    // 학적 명단의 전화번호와 매칭되는지 확인
    Gbsw gbsw = gbswRepository.findByPhoneNumber(phoneNumber)
        .orElseThrow(() -> new CustomException(GbswErrorCode.NOT_REGISTERED_PHONE_NUMBER));

    // 회원가입 시도하는 전화번호가 이미 가입된 계정인지 확인
    if (userRepository.existsByGbsw(gbsw)) {
      throw new CustomException(UserErrorCode.ALREADY_REGISTERED);
    }

    User user = User.builder()
        .gbsw(gbsw)
        .loginId(request.loginId())
        .passwordHash(passwordEncoder.encode(request.password()))
        .name(request.name())
        .phoneNumber(phoneNumber)
        .build();
    userRepository.save(user);

    // 가입이 완전히 끝난 뒤에 티켓을 지운다 (save 실패 시 재시도할 수 있도록 티켓을 보존).
    redisRepository.delete(RedisKeyType.SIGN_UP_TICKET, request.ticket());
  }

}
