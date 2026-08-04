package com.remake.gone.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 DTO.
 *
 * @param loginId  로그인 ID
 * @param password 평문 비밀번호 (서버에서 해시와 비교)
 */
public record LoginRequest(
    @NotBlank String loginId,
    @NotBlank String password
) {}
