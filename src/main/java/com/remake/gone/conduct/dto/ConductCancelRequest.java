package com.remake.gone.conduct.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 상/벌점 취소 요청 DTO.
 *
 * @param cancelReason 취소 사유 (필수, 최대 500자)
 */
public record ConductCancelRequest(
    @NotBlank @Size(max = 500) String cancelReason
) {}
