package com.remake.gone.meal.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.meal.dto.MealsResponse;
import com.remake.gone.meal.enums.MealType;
import com.remake.gone.meal.service.MealService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NEIS 급식 정보 조회 API 컨트롤러.
 *
 * <p>학교 전체 공통 정보라 인증 없이 조회할 수 있다({@code SecurityConfig} 참고).
 */
@RestController
@RequestMapping("/api/v1/meals")
@RequiredArgsConstructor
public class MealController {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final MealService mealService;

  /**
   * 급식 정보를 조회합니다.
   *
   * @param date     조회할 날짜(yyyyMMdd). 생략 시 오늘(KST)
   * @param mealType 걸러낼 급식 구분(조식/중식/석식). 생략 시 그날 전체
   * @return 급식 조회 응답
   */
  @GetMapping
  public ApiResponse<MealsResponse> getMeals(
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate date,
      @RequestParam(required = false) MealType mealType
  ) {
    LocalDate targetDate = date != null ? date : LocalDate.now(KST);
    MealsResponse response = mealService.getMeals(targetDate, mealType);
    return ApiResponse.success(response, "급식 정보를 조회했습니다.");
  }
}
