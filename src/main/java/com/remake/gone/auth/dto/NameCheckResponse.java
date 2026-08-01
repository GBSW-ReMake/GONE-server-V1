package com.remake.gone.auth.dto;

/**
 * 별명 중복확인 응답 DTO.
 *
 * @param available 사용 가능 여부 — {@code true}면 아직 사용 중인 계정이 없음
 */
public record NameCheckResponse(boolean available) {}
