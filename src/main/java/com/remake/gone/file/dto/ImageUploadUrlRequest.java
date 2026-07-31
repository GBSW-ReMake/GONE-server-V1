package com.remake.gone.file.dto;

public record ImageUploadUrlRequest(
    String fileName,
    String contentType,
    long fileSize
) {}