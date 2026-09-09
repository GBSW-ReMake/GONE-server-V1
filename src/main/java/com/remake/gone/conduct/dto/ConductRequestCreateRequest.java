package com.remake.gone.conduct.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 상/벌점 요청 생성 DTO.
 *
 * @param studentUserId 상/벌점 대상 학생 사용자 ID
 * @param assigneeUserId 처리 담당자 사용자 ID (TEACHER 또는 ADMIN)
 * @param categoryId    요청 카테고리 ID ({@code GET /api/v1/conduct-records/categories} 목록 참조)
 * @param detail        추가 상세 사유(선택, 최대 500자)
 */
public record ConductRequestCreateRequest(
    @NotNull Long studentUserId,
    @NotNull Long assigneeUserId,
    @NotNull Long categoryId,
    @Size(max = 500) String detail
) {}
