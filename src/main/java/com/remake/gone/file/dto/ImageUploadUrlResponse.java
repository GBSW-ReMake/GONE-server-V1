package com.remake.gone.file.dto;

/**
 * 프로필 이미지 업로드 URL 발급 응답 DTO.
 *
 * @param uploadUrl 클라이언트가 직접 PUT할 presigned 업로드 URL
 * @param key       발급된 객체의 저장 key
 */
public record ImageUploadUrlResponse(
    String uploadUrl,
    String key
) {}

