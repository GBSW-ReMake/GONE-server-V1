package com.remake.gone.meal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.meal.dto.MealsResponse;
import com.remake.gone.meal.enums.MealType;
import com.remake.gone.meal.service.MealService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link MealController}에 대한 웹 계층(슬라이스) 테스트.
 */
@WebMvcTest(MealController.class)
@AutoConfigureMockMvc(addFilters = false)
class MealControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MealService mealService;

  @Nested
  @DisplayName("GET /api/v1/meals")
  class GetMeals {

    @Test
    @DisplayName("date/mealType 파라미터를 그대로 서비스에 전달한다")
    void passesQueryParamsToService() throws Exception {
      given(mealService.getMeals(LocalDate.of(2026, 8, 10), MealType.LUNCH))
          .willReturn(new MealsResponse("20260810", List.of()));

      mockMvc.perform(get("/api/v1/meals").param("date", "20260810").param("mealType", "LUNCH"))
          .andExpect(status().isOk());

      verify(mealService).getMeals(LocalDate.of(2026, 8, 10), MealType.LUNCH);
    }

    @Test
    @DisplayName("파라미터를 생략하면 mealType은 null로 전달한다")
    void passesNullMealTypeWhenOmitted() throws Exception {
      given(mealService.getMeals(eq(LocalDate.of(2026, 8, 10)), isNull()))
          .willReturn(new MealsResponse("20260810", List.of()));

      mockMvc.perform(get("/api/v1/meals").param("date", "20260810"))
          .andExpect(status().isOk());

      verify(mealService).getMeals(eq(LocalDate.of(2026, 8, 10)), isNull());
    }

    @Test
    @DisplayName("date가 날짜로 파싱될 수 없는 값이면 400을 반환한다")
    void failsWhenDateFormatInvalid() throws Exception {
      // GlobalExceptionHandler에 MethodArgumentTypeMismatchException 핸들러가 추가되면서
      // (#41) 이제 500이 아니라 400으로 정확히 처리된다.
      mockMvc.perform(get("/api/v1/meals").param("date", "not-a-date"))
          .andExpect(status().isBadRequest());
    }
  }
}
