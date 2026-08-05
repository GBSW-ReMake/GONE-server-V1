package com.remake.gone.meal.dto;

import java.util.List;

/**
 * 급식 한 끼(조식/중식/석식 중 하나)의 응답.
 *
 * @param mealType 식사명(조식/중식/석식)
 * @param dishes   요리명 목록(NEIS 원본의 {@code <br/>} 구분을 리스트로 변환)
 * @param calorie  칼로리 정보
 */
public record MealResponse(String mealType, List<String> dishes, String calorie) {}
