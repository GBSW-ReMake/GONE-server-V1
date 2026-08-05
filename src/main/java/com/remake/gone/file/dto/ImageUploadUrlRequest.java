package com.remake.gone.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 프로필 이미지 업로드 URL 발급 요청 DTO.
 *
 * @param fileName    클라이언트가 보낸 원본 파일명
 * @param contentType 업로드할 파일의 MIME 타입 (예: {@code "image/jpeg"})
 * @param fileSize    업로드할 파일의 크기(byte)
 */
public record ImageUploadUrlRequest(
    @NotBlank String fileName,
    @NotBlank String contentType,
    @Positive long fileSize
) {}