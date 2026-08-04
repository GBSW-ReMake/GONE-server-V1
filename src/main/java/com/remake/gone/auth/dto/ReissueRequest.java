package com.remake.gone.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Access Token 재발급 요청 DTO.
 *
 * @param refreshToken 로그인 시 발급받은 Refresh Token
 */
public record ReissueRequest(
    @NotBlank String refreshToken
) {}
