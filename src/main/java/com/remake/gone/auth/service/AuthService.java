package com.remake.gone.auth.service;

import com.remake.gone.auth.dto.LoginRequest;
import com.remake.gone.auth.dto.ReissueRequest;
import com.remake.gone.auth.dto.SignUpRequest;
import com.remake.gone.auth.dto.TokenResponse;
import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.common.security.JwtProperties;
import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
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
  private final JwtProvider jwtProvider;
  private final JwtProperties jwtProperties;

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

    // 로그인 ID 중복 확인
    if (!isLoginIdAvailable(request.loginId())) {
      throw new CustomException(UserErrorCode.LOGIN_ID_ALREADY_EXISTS);
    }

    // 별명 중복 확인
    if (!isNameAvailable(request.name())) {
      throw new CustomException(UserErrorCode.NAME_ALREADY_EXISTS);
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

  /**
   * 로그인 ID를 사용할 수 있는지 확인합니다.
   *
   * @param loginId 확인할 로그인 ID
   * @return 사용 가능하면(중복이 없으면) {@code true}
   */
  public boolean isLoginIdAvailable(String loginId) {
    return !userRepository.existsByLoginId(loginId);
  }

  /**
   * 별명을 사용할 수 있는지 확인합니다.
   *
   * @param name 확인할 별명
   * @return 사용 가능하면(중복이 없으면) {@code true}
   */
  public boolean isNameAvailable(String name) {
    return !userRepository.existsByName(name);
  }

  /**
   * 로그인 ID/비밀번호를 검증하고 Access/Refresh Token을 발급합니다.
   *
   * @param request 로그인 요청 정보
   * @return 발급된 토큰 정보
   */
  @Transactional(readOnly = true)
  public TokenResponse login(LoginRequest request) {
    User user = userRepository.findByLoginId(request.loginId())
        .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_CREDENTIALS));

    // 아이디가 존재하는지와 비밀번호가 맞는지를 같은 에러로 응답한다.
    // 둘을 구분해서 응답하면 공격자가 "아이디는 맞다"는 사실만으로 계정 존재 여부를 알아낼 수 있다.
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new CustomException(AuthErrorCode.INVALID_CREDENTIALS);
    }

    return issueTokens(user.getId());
  }

  /**
   * Refresh Token을 검증하고 Access/Refresh Token을 재발급합니다.
   *
   * <p>재발급 때마다 Refresh Token도 새로 발급(rotation)해 Redis 값을 교체한다. 탈취된 옛
   * Refresh Token이 재사용되면 Redis에 저장된 최신 값과 달라 아래 일치 검사에서 걸러진다.
   *
   * @param request 재발급 요청 정보 (기존 Refresh Token)
   * @return 재발급된 토큰 정보
   */
  @Transactional
  public TokenResponse reissue(ReissueRequest request) {
    Long userId;
    try {
      userId = jwtProvider.getUserIdFromRefreshToken(request.refreshToken());
    } catch (JwtException | IllegalArgumentException e) {
      // 서명 위조, 만료, Access Token을 잘못 넣은 경우 등을 모두 같은 에러로 응답한다.
      throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    String savedRefreshToken =
        redisRepository.find(RedisKeyType.REFRESH_TOKEN, String.valueOf(userId), String.class);

    // Redis에 없으면 만료/로그아웃된 것이고, 값이 다르면 이미 재발급에 쓰여 폐기된
    // (탈취되어 재사용 시도 중인) 토큰이라는 뜻이다. 두 경우 모두 재발급을 거부한다.
    if (savedRefreshToken == null || !savedRefreshToken.equals(request.refreshToken())) {
      throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    return issueTokens(userId);
  }

  /**
   * 로그아웃 처리를 합니다. Redis에 저장된 Refresh Token을 삭제해 이후 재발급을 막습니다.
   *
   * <p>Access Token 자체는 만료 전까지 여전히 유효하지만(자체 무효화는 이번 범위 밖),
   * Refresh Token이 사라지므로 만료 후에는 재로그인이 필요해진다.
   *
   * @param userId 로그아웃할 사용자 ID (Access Token에서 추출됨)
   */
  @Transactional
  public void logout(Long userId) {
    redisRepository.delete(RedisKeyType.REFRESH_TOKEN, String.valueOf(userId));
  }

  private TokenResponse issueTokens(Long userId) {
    String accessToken = jwtProvider.createAccessToken(userId);
    String refreshToken = jwtProvider.createRefreshToken(userId);

    redisRepository.save(RedisKeyType.REFRESH_TOKEN, String.valueOf(userId), refreshToken);

    long accessTokenExpiresInSeconds = jwtProperties.accessTokenExpiration() / 1000;
    return new TokenResponse(accessToken, refreshToken, accessTokenExpiresInSeconds);
  }
}
