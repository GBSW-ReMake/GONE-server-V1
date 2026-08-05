package com.remake.gone.common.security;

import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 로그인 ID 또는 전화번호로 {@link AuthUserDetails}를 로드한다. 로그인 시점
 * ({@code AuthenticationManager})에서만 쓰인다.
 *
 * <p>{@code @Component}가 아니라 {@code SecurityConfig}의 {@code @Bean} 메서드로 등록한다
 * ({@link JwtProvider} 클래스 주석 참고).
 */
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;

  @Override
  public UserDetails loadUserByUsername(String identifier) {
    User user = userRepository.findFirstByLoginIdOrPhoneNumber(identifier, identifier)
        .orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다: " + identifier));

    Set<String> roleCodes = Set.copyOf(userRoleRepository.findRoleCodesByUserId(user.getId()));

    return new AuthUserDetails(user.getId(), user.getLoginId(), user.getPasswordHash(), roleCodes);
  }
}
