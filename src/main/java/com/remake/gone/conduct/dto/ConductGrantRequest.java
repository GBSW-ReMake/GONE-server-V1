package com.remake.gone.conduct.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 상/벌점 부여 요청 DTO.
 *
 * @param studentUserId 대상 학생 사용자 ID
 * @param categoryId    부여할 카테고리 ID ({@code GET /api/v1/conduct-records/categories} 목록 참조)
 * @param detail        추가 상세 사유(선택, 최대 500자)
 */
public record ConductGrantRequest(
    @NotNull Long studentUserId,
    @NotNull Long categoryId,
    @Size(max = 500) String detail
) {}
