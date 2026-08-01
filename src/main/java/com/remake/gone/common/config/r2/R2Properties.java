package com.remake.gone.common.config.r2;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cloudflare R2 연동에 필요한 설정 값.
 *
 * @param accountId Cloudflare 계정 ID
 * @param accessKey R2 액세스 키
 * @param secretKey R2 시크릿 키
 * @param bucket    버킷 이름
 * @param endpoint  R2 S3 호환 엔드포인트 URL
 */
@ConfigurationProperties(prefix = "r2")
@Validated
public record R2Properties(
    @NotBlank String accountId,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucket,
    @NotBlank String endpoint
) {}