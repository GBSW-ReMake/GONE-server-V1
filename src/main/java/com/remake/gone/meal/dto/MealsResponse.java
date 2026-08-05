package com.remake.gone.meal.dto;

import java.util.List;

/**
 * 특정 날짜의 급식 조회 응답.
 *
 * @param date  조회한 날짜(yyyyMMdd)
 * @param meals 그날의 급식 목록. 데이터가 없는 날(주말/방학 등)은 빈 리스트
 */
public record MealsResponse(String date, List<MealResponse> meals) {}
