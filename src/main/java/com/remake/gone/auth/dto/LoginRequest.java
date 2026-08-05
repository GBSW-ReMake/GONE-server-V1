package com.remake.gone.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 DTO.
 *
 * @param identifier 로그인 ID 또는 가입 시 등록한 전화번호
 * @param password   평문 비밀번호 (서버에서 해시와 비교)
 */
public record LoginRequest(
    @NotBlank String identifier,
    @NotBlank String password
) {}
