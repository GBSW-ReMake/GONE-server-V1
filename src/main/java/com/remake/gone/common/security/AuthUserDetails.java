package com.remake.gone.common.security;

import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인 시점의 자격증명 검증({@code AuthenticationManager})에서만 쓰이는 {@link UserDetails} 구현체.
 *
 * <p>{@code User} 엔티티를 그대로 구현체로 쓰지 않고 감싸는 이유는, JPA 엔티티를 Spring Security
 * 인터페이스에 결합시키지 않기 위함이다. 로그인 이후 매 요청의 JWT 인증은 이 클래스와 무관하게
 * {@link UserPrincipal} + {@link JwtAuthenticationFilter}가 stateless하게 처리한다 — 이 둘은
 * 서로 다른 관심사이며, 이 클래스가 있다고 해서 요청마다 DB를 조회하지 않는다.
 *
 * <p>계정 잠김/정지 개념이 아직 없어 {@link #isEnabled()} 등은 전부 {@code true}로 고정한다.
 * 나중에 계정 상태 컬럼이 생기면 그 값을 반영하도록 확장한다.
 *
 * @param userId      인증된 사용자 ID
 * @param loginId     로그인 ID (username)
 * @param passwordHash 저장된 비밀번호 해시
 * @param roleCodes   보유한 역할 코드 목록
 */
public record AuthUserDetails(
    Long userId,
    String loginId,
    String passwordHash,
    Set<String> roleCodes
) implements UserDetails {

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return roleCodes.stream()
        .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
        .toList();
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return loginId;
  }
}
