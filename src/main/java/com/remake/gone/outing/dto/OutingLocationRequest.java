package com.remake.gone.outing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 출발/도착 보고 요청 DTO(#43).
 *
 * @param latitude  보고 시점의 위도
 * @param longitude 보고 시점의 경도
 */
public record OutingLocationRequest(
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude
) {}
