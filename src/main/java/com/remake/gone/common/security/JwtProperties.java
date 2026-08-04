package com.remake.gone.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 발급/검증에 필요한 설정 값.
 *
 * @param secret                 서명에 사용할 비밀키 (HMAC-SHA256, Base64 인코딩 문자열)
 * @param accessTokenExpiration  Access Token 만료 시간(밀리초)
 * @param refreshTokenExpiration Refresh Token 만료 시간(밀리초)
 */
@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(
    @NotBlank String secret,
    @NotNull Long accessTokenExpiration,
    @NotNull Long refreshTokenExpiration
) {}
