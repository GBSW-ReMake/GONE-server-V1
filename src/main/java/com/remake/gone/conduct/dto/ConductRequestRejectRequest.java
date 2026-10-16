package com.remake.gone.conduct.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 상/벌점 요청 거절 DTO.
 *
 * @param reason 거절 사유 (필수, 최대 500자)
 */
public record ConductRequestRejectRequest(
    @NotBlank
    @Size(max = 500)
    String reason
) {}
