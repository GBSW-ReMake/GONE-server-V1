package com.remake.gone.common.config;

import com.remake.gone.common.security.JwtAuthenticationFilter;
import com.remake.gone.common.security.JwtProperties;
import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.common.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * {@link RestAuthenticationEntryPoint})을 여기서 {@code @Bean} 메서드로 직접 등록한다.
 * {@code @Component}로 두면 {@code @WebMvcTest} 슬라이스가 이들을 스캔에서 제외해
 * {@code SecurityConfig} 구성 자체가 깨지기 때문이다.
 */
@Configuration
@EnableWebSecurity
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
        // 로그아웃만 Access Token 인증이 필요하고, 나머지 기존 API는 그대로 permitAll 유지.
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
            .anyRequest().permitAll())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
