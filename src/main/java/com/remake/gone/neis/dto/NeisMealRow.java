package com.remake.gone.neis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NEIS {@code mealServiceDietInfo} 응답의 {@code row} 한 건을 그대로 옮겨 담는 원본 DTO.
 *
 * <p>필드명은 NEIS 응답 그대로(대문자 스네이크 표기)를 {@link JsonProperty}로 매핑한다. 우리가
 * 실제로 쓰는 필드만 담고, 알레르기 정보({@code NTR_INFO} 등)는 이번 이슈 범위 밖이라 생략한다.
 *
 * @param mealTypeName 식사명(조식/중식/석식)
 * @param dishName     요리명(여러 줄이 {@code <br/>}로 구분된 문자열)
 * @param calorie      칼로리 정보
 */
public record NeisMealRow(
    @JsonProperty("MMEAL_SC_NM") String mealTypeName,
    @JsonProperty("DDISH_NM") String dishName,
    @JsonProperty("CAL_INFO") String calorie
) {}
