package com.remake.gone.auth.dto;

/**
 * 로그인/재발급 성공 시 반환하는 토큰 정보. 두 API의 응답 모양이 동일해 공유한다.
 *
 * @param accessToken          발급된 Access Token
 * @param refreshToken         발급된 Refresh Token
 * @param accessTokenExpiresIn Access Token 만료까지 남은 시간(초)
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    long accessTokenExpiresIn
) {}
