package com.remake.gone.outing.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 출발/도착 보고 시 학교 반경 판정에 쓰이는 설정 값(#43).
 *
 * @param schoolLatitude     학교 위도
 * @param schoolLongitude    학교 경도
 * @param schoolRadiusMeters 학교 반경(미터). 이 범위 밖에서는 출발/도착을 보고할 수 없다
 */
@ConfigurationProperties(prefix = "outing")
@Validated
public record OutingProperties(
    @NotNull Double schoolLatitude,
    @NotNull Double schoolLongitude,
    @NotNull @Positive Integer schoolRadiusMeters
) {}
