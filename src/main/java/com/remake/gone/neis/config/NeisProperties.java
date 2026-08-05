package com.remake.gone.neis.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 나이스(NEIS) 오픈 API 연동에 필요한 설정 값.
 *
 * <p>우리 학교(경북소프트웨어마이스터고등학교) 고정값이라 클라이언트가 보내지 않고 서버가
 * 항상 이 값으로 조회한다.
 *
 * @param apiKey          NEIS 오픈 API 인증키
 * @param atptOfcdcScCode 시도교육청코드
 * @param sdSchulCode     행정표준코드(학교 코드)
 */
@ConfigurationProperties(prefix = "neis")
@Validated
public record NeisProperties(
    @NotBlank String apiKey,
    @NotBlank String atptOfcdcScCode,
    @NotBlank String sdSchulCode
) {}
