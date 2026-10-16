package com.remake.gone.conduct.dto;

/**
 * 상/벌점 요청 승인 DTO.
 *
 * @param categoryId 카테고리 오버라이드 ID (생략 시 요청 원본 카테고리 사용)
 * @param detail     상세 사유 오버라이드 (생략 시 요청 원본 사유 사용)
 */
public record ConductRequestApproveRequest(
    Long categoryId,
    String detail
) {}
