package com.remake.gone.common.config;

import com.remake.gone.common.security.JwtAuthenticationFilter;
import com.remake.gone.common.security.JwtProperties;
import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.common.security.RestAuthenticationEntryPoint;
import com.remake.gone.common.security.UserDetailsServiceImpl;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security 설정.
 *
 * <p>토큰 기반 인증을 사용하는 REST API이므로 세션을 사용하지 않고,
 * CSRF/폼 로그인/HTTP Basic 등 기본 인증 방식은 비활성화한다.
 *
 * <p>JWT 관련 빈({@link JwtProvider}, {@link JwtAuthenticationFilter},
 * {@link RestAuthenticationEntryPoint})과 로그인 검증용 빈({@link UserDetailsServiceImpl},
 * {@link AuthenticationManager})을 여기서 {@code @Bean} 메서드로 직접 등록한다. {@code @Component}로
 * 두면 {@code @WebMvcTest} 슬라이스가 이들을 스캔에서 제외해 {@code SecurityConfig} 구성 자체가
 * 깨지기 때문이다.
 *
 * <p>{@link AuthenticationManager}/{@link UserDetailsServiceImpl}은 로그인 시점의 자격증명
 * 검증에만 쓰인다. 로그인 이후 매 요청의 인증은 여전히 {@link JwtAuthenticationFilter}가 JWT
 * 서명만으로 stateless하게 처리하며, 이 두 흐름은 서로 대체 관계가 아니다.
 *
 * <p>{@link EnableMethodSecurity}로 컨트롤러 메서드의 {@code @PreAuthorize} 등 선언적 인가를
 * 활성화한다(#30에서 처음 사용 — 이게 없으면 {@code @PreAuthorize}를 붙여도 조용히 무시된다).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  /**
   * 비밀번호 해시/검증에 사용할 인코더.
   *
   * @return BCrypt 기반 {@link PasswordEncoder}
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * 로그인 ID로 사용자를 로드하는 서비스. 로그인 시점의 자격증명 검증에서만 쓰인다.
   *
   * @param userRepository     사용자 조회용 리포지토리
   * @param userRoleRepository 역할 조회용 리포지토리
   * @return 구성된 {@link UserDetailsServiceImpl}
   */
  @Bean
  public UserDetailsServiceImpl userDetailsService(
      UserRepository userRepository,
      UserRoleRepository userRoleRepository
  ) {
    return new UserDetailsServiceImpl(userRepository, userRoleRepository);
  }

  /**
   * {@link UserDetailsServiceImpl}과 {@link PasswordEncoder}를 이용해 로그인 자격증명을
   * 검증하는 {@link AuthenticationManager}.
   *
   * @param userDetailsService 사용자 로드에 쓸 서비스
   * @param passwordEncoder    비밀번호 검증에 쓸 인코더
   * @return 구성된 {@link AuthenticationManager}
   */
  @Bean
  public AuthenticationManager authenticationManager(
      UserDetailsServiceImpl userDetailsService,
      PasswordEncoder passwordEncoder
  ) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }

  /**
   * Access/Refresh Token 발급·검증기.
   *
   * @param jwtProperties JWT 설정 값
   * @return 구성된 {@link JwtProvider}
   */
  @Bean
  public JwtProvider jwtProvider(JwtProperties jwtProperties) {
    return new JwtProvider(jwtProperties);
  }

  /**
   * Access Token을 읽어 {@code SecurityContext}를 채우는 필터.
   *
   * @param jwtProvider 토큰 검증에 사용할 {@link JwtProvider}
   * @return 구성된 {@link JwtAuthenticationFilter}
   */
  @Bean
  public JwtAuthenticationFilter jwtAuthenticationFilter(JwtProvider jwtProvider) {
    return new JwtAuthenticationFilter(jwtProvider);
  }

  /**
   * 인증 필요한 API에 인증 없이 접근했을 때 {@code ApiResponse} 포맷으로 응답하는 진입점.
   *
   * @param objectMapper 응답 JSON 직렬화에 사용할 {@link ObjectMapper}
   * @return 구성된 {@link RestAuthenticationEntryPoint}
   */
  @Bean
  public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return new RestAuthenticationEntryPoint(objectMapper);
  }

  /**
   * 세션을 사용하지 않는 stateless REST API용 보안 필터 체인을 구성한다.
   *
   * @param http                        설정 대상 {@link HttpSecurity}
   * @param jwtAuthenticationFilter     JWT 인증 필터
   * @param restAuthenticationEntryPoint 인증 실패 응답 진입점
   * @return 구성된 {@link SecurityFilterChain}
   * @throws Exception 필터 체인 구성 중 오류가 발생한 경우
   */
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RestAuthenticationEntryPoint restAuthenticationEntryPoint
  ) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .formLogin(formLogin -> formLogin.disable())
        .httpBasic(httpBasic -> httpBasic.disable())
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exception ->
            exception.authenticationEntryPoint(restAuthenticationEntryPoint))
        // 로그아웃, 본인 정보 조회/변경, 프로필 사진 업로드, 시간표(본인 학급 자동 조회)는
        // Access Token 인증이 필요하고, 나머지 기존 API는 그대로 permitAll 유지.
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
            .requestMatchers("/api/v1/users/**").authenticated()
            .requestMatchers("/api/v1/files/**").authenticated()
            .requestMatchers("/api/v1/timetables/**").authenticated()
            .requestMatchers("/api/v1/outings/**").authenticated()
            .requestMatchers("/api/v1/school-camps/**").authenticated()
            .anyRequest().permitAll())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
