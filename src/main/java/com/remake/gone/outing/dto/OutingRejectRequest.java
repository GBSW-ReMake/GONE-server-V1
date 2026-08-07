package com.remake.gone.outing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 외출증 거절 요청 DTO.
 *
 * @param rejectedReason 거절 사유
 */
public record OutingRejectRequest(
    @NotBlank
    @Size(max = 200, message = "거절 사유는 200자 이하로 입력해주세요")
    String rejectedReason
) {}
