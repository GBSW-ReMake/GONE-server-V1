package com.remake.gone.common.config;

import com.remake.gone.common.security.JwtAuthenticationFilter;
import com.remake.gone.common.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
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

/**
 * Spring Security 설정.
 *
 * <p>토큰 기반 인증을 사용하는 REST API이므로 세션을 사용하지 않고,
 * CSRF/폼 로그인/HTTP Basic 등 기본 인증 방식은 비활성화한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

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
   * 세션을 사용하지 않는 stateless REST API용 보안 필터 체인을 구성한다.
   *
   * @param http 설정 대상 {@link HttpSecurity}
   * @return 구성된 {@link SecurityFilterChain}
   * @throws Exception 필터 체인 구성 중 오류가 발생한 경우
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
