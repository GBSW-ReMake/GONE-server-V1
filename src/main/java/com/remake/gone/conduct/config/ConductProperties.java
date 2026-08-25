package com.remake.gone.conduct.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 상/벌점 도메인 설정 값.
 *
 * @param demeritThreshold 누적 벌점 임계치. 절댓값 기준으로 이 값 이상이면 임계치 초과로 판정한다
 */
@ConfigurationProperties(prefix = "conduct")
@Validated
public record ConductProperties(
    @NotNull @Positive Integer demeritThreshold
) {}
