package com.remake.gone.auth.service;

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
import com.remake.gone.gbsw.utils.GbswUtils;
import com.remake.gone.role.entity.Role;
import com.remake.gone.role.entity.UserRole;
import com.remake.gone.role.repository.RoleRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.enums.UserStatus;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증(Auth) 관련 비즈니스 로직을 처리하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

  /** 교사 기본 닉네임의 랜덤 구분자 자릿수 (예: {@code "T-a1b-김선생"}의 {@code "a1b"}). */
  private static final int TEACHER_NAME_SUFFIX_LENGTH = 3;

  /** 교사 기본 닉네임이 동명이인과 겹칠 때 랜덤 구분자를 다시 뽑아보는 최대 횟수. */
  private static final int MAX_TEACHER_NAME_ATTEMPTS = 5;

  private final UserRepository userRepository;
  private final GbswRepository gbswRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;
  private final RedisRepository redisRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;
  private final JwtProperties jwtProperties;
  private final AuthenticationManager authenticationManager;

  /**
   * 휴대폰 인증(signUpTicket)을 검증한 뒤, 명단(Gbsw)과 연결된 회원 계정을 생성합니다.
   *
   * <p>가입과 동시에 {@link Gbsw#getType()}(학생/선생님)에 대응하는 기본 역할을 부여합니다.
   * 학생회/선도부 등 부서 역할은 이 시점에 부여하지 않습니다 — 관리자가 별도 API로 나중에
   * 부여합니다.
   *
   * <p>가입 직후 클라이언트가 별도 로그인 없이 곧바로 이름/프로필 사진 설정 화면으로 이어갈 수
   * 있도록, {@code login()}과 동일하게 Access/Refresh Token을 바로 발급해 반환한다.
   *
   * @param request 회원가입 요청 정보
   * @return 발급된 토큰 정보
   */
  @Transactional
  public TokenResponse signUp(SignUpRequest request) {
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

    User user = User.builder()
        .gbsw(gbsw)
        .loginId(request.loginId())
        .passwordHash(passwordEncoder.encode(request.password()))
        .name(generateDefaultName(gbsw))
        .phoneNumber(phoneNumber)
        .status(UserStatus.ACTIVE)
        .build();
    userRepository.save(user);

    // Gbsw.type(STUDENT/TEACHER)에 대응하는 기본 역할만 부여한다. 학생회/선도부 등 다른 role은
    // Gbsw에 어떤 정보가 있든 여기서 절대 추론/부여하지 않는다 (관리자 API로만 사후 부여).
    Role defaultRole = roleRepository.findByCode(gbsw.getType().name())
        .orElseThrow(() -> new IllegalStateException(
            "기본 역할 시드 데이터가 없습니다: " + gbsw.getType().name()));
    userRoleRepository.save(UserRole.builder().user(user).role(defaultRole).build());

    // 가입이 완전히 끝난 뒤에 티켓을 지운다 (save 실패 시 재시도할 수 있도록 티켓을 보존).
    redisRepository.delete(RedisKeyType.SIGN_UP_TICKET, request.ticket());

    return issueTokens(user.getId(), Set.of(defaultRole.getCode()));
  }

  /**
   * 학적(Gbsw) 정보로 회원가입 시 채워줄 기본 닉네임을 생성합니다.
   *
   * <p>학생은 학번(학년+반+번호)과 학적 실명을 조합하고, 교사는 학번이 없으므로 랜덤 구분자와
   * 학적 실명을 조합합니다. 자세한 형식과 유일성 근거는 {@code docs/20.md} 참고.
   *
   * @param gbsw 가입 대상 학적 레코드
   * @return 생성된 기본 닉네임
   */
  private String generateDefaultName(Gbsw gbsw) {
    if (gbsw.getType() == GbswType.TEACHER) {
      return generateTeacherDefaultName(gbsw);
    }
    return generateStudentDefaultName(gbsw);
  }

  private String generateStudentDefaultName(Gbsw gbsw) {
    if (gbsw.getGrade() == null || gbsw.getClassNo() == null || gbsw.getNumber() == null) {
      // 학생 명단이면 항상 채워져 있어야 하는 값들이다 — 엑셀 명단 입력 오류 등 데이터 이상.
      throw new IllegalStateException("학생 명단에 학번 정보가 없습니다: gbswId=" + gbsw.getId());
    }
    // 포맷 계산(classNo 0채움 안 함, 반 10개 미만 전제) 자체는 GbswUtils.studentNumber와
    // 공유한다 — 그 전제가 깨지면 고칠 곳이 한 곳으로 줄어든다.
    return GbswUtils.studentNumber(gbsw) + gbsw.getName();
  }

  private String generateTeacherDefaultName(Gbsw gbsw) {
    String candidate = "T-%s-%s".formatted(randomNameSuffix(), gbsw.getName());
    for (int attempt = 1;
        attempt < MAX_TEACHER_NAME_ATTEMPTS && userRepository.existsByName(candidate);
        attempt++) {
      candidate = "T-%s-%s".formatted(randomNameSuffix(), gbsw.getName());
    }
    // 재시도를 다 써도 겹치는 극희귀 경우, save() 시점의 DB unique 위반이 기존 경합 처리
    // 경로(DataIntegrityViolationException -> 409)로 안전하게 흡수한다.
    return candidate;
  }

  private String randomNameSuffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, TEACHER_NAME_SUFFIX_LENGTH);
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
   * 로그인 ID 또는 전화번호 + 비밀번호를 검증하고 Access/Refresh Token을 발급합니다.
   *
   * <p>자격증명 검증은 {@link AuthenticationManager}에 위임한다 ({@code UserDetailsServiceImpl}
   * + {@code PasswordEncoder} 기반). 로그인 이후 매 요청의 JWT 인증은 이 흐름과 무관하게
   * 여전히 stateless하게 처리된다.
   *
   * @param request 로그인 요청 정보
   * @return 발급된 토큰 정보
   */
  @Transactional(readOnly = true)
  public TokenResponse login(LoginRequest request) {
    Authentication authentication;
    try {
      authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.identifier(), request.password()));
    } catch (AuthenticationException e) {
      // 아이디가 없을 때와 비밀번호가 틀렸을 때를 같은 에러로 응답한다.
      // 둘을 구분해서 응답하면 공격자가 "아이디는 맞다"는 사실만으로 계정 존재 여부를 알아낼 수 있다.
      throw new CustomException(AuthErrorCode.INVALID_CREDENTIALS);
    }

    AuthUserDetails userDetails = (AuthUserDetails) authentication.getPrincipal();
    return issueTokens(userDetails.userId(), userDetails.roleCodes());
  }

  /**
   * Refresh Token을 검증하고 Access/Refresh Token을 재발급합니다.
   *
   * <p>재발급 때마다 Refresh Token도 새로 발급(rotation)해 Redis 값을 교체한다. 탈취된 옛
   * Refresh Token이 재사용되면 Redis에 저장된 최신 값과 달라 아래 일치 검사에서 걸러진다.
   * 역할(role)은 Refresh Token이 아니라 매번 최신값을 다시 조회해서 발급한다 (역할이 바뀐
   * 뒤에도 옛 역할이 계속 실려 나가는 것을 방지).
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

    Set<String> roleCodes = Set.copyOf(userRoleRepository.findRoleCodesByUserId(userId));
    return issueTokens(userId, roleCodes);
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

  private TokenResponse issueTokens(Long userId, Set<String> roleCodes) {
    String accessToken = jwtProvider.createAccessToken(userId, roleCodes);
    String refreshToken = jwtProvider.createRefreshToken(userId);

    redisRepository.save(RedisKeyType.REFRESH_TOKEN, String.valueOf(userId), refreshToken);

    long accessTokenExpiresInSeconds = jwtProperties.accessTokenExpiration() / 1000;
    return new TokenResponse(accessToken, refreshToken, accessTokenExpiresInSeconds);
  }
}
