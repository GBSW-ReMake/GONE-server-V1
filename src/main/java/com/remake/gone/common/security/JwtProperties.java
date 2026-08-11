package com.remake.gone.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 발급/검증에 필요한 설정 값.
 *
 * <p>Access Token과 Refresh Token은 서로 다른 서명 키를 쓴다(#52) — 한쪽 키가 유출돼도
 * 반대쪽 토큰은 위조할 수 없도록 피해 범위를 분리하기 위함이다.
 *
 * @param accessTokenSecret      Access Token 서명에 사용할 비밀키 (HMAC-SHA256, Base64 인코딩 문자열)
 * @param refreshTokenSecret     Refresh Token 서명에 사용할 비밀키 (HMAC-SHA256, Base64 인코딩 문자열)
 * @param accessTokenExpiration  Access Token 만료 시간(밀리초)
 * @param refreshTokenExpiration Refresh Token 만료 시간(밀리초)
 */
@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(
    @NotBlank String accessTokenSecret,
    @NotBlank String refreshTokenSecret,
    @NotNull Long accessTokenExpiration,
    @NotNull Long refreshTokenExpiration
) {}
