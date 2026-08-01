package com.remake.gone.file.dto;

/**
 * 프로필 이미지 업로드 확정 요청 DTO.
 *
 * @param key 확정할 객체의 저장 key
 */
public record ProfileImageConfirmRequest(
    String key
) {}
