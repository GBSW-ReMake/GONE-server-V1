package com.remake.gone.conduct.dto;

import jakarta.validation.constraints.Size;

/**
 * 상/벌점 정정 요청 DTO.
 *
 * @param categoryId 변경할 카테고리 ID (생략 시 기존 값 유지)
 * @param detail     변경할 상세 사유 (생략 시 기존 값 유지, 최대 500자)
 */
public record ConductAmendRequest(
    Long categoryId,
    @Size(max = 500) String detail
) {}
