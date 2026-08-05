package com.remake.gone.meal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.meal.dto.MealResponse;
import com.remake.gone.meal.dto.MealsResponse;
import com.remake.gone.meal.enums.MealType;
import com.remake.gone.neis.NeisClient;
import com.remake.gone.neis.dto.NeisMealRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MealService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class MealServiceTest {

  @Mock
  private NeisClient neisClient;

  @Mock
  private RedisRepository redisRepository;

  @InjectMocks
  private MealService mealService;

  private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

  @Nested
  @DisplayName("getMeals")
  class GetMeals {

    @Test
    @DisplayName("캐시가 있으면 NEIS를 호출하지 않고 캐시된 값을 반환한다")
    void returnsCachedResponseWithoutCallingNeis() {
      MealsResponse cached = new MealsResponse(
          "20260810", List.of(new MealResponse("조식", List.of("흑미밥"), "926.1 Kcal")));
      given(redisRepository.find(RedisKeyType.MEAL_INFO, "20260810", MealsResponse.class))
          .willReturn(cached);

      MealsResponse response = mealService.getMeals(DATE, null);

      assertThat(response).isEqualTo(cached);
      verify(neisClient, never()).fetch(anyString(), any(), anyString(), eq(NeisMealRow.class));
    }

    @Test
    @DisplayName("캐시가 없으면 NEIS에서 조회해 <br/>로 요리명을 분리하고 캐싱한다")
    void fetchesFromNeisAndCachesOnCacheMiss() {
      given(redisRepository.find(RedisKeyType.MEAL_INFO, "20260810", MealsResponse.class))
          .willReturn(null);
      given(neisClient.fetch(
          eq("/mealServiceDietInfo"), any(), eq("mealServiceDietInfo"), eq(NeisMealRow.class)))
          .willReturn(List.of(new NeisMealRow("조식", "흑미밥<br/>미역국 (5.6)", "926.1 Kcal")));

      MealsResponse response = mealService.getMeals(DATE, null);

      assertThat(response.date()).isEqualTo("20260810");
      assertThat(response.meals()).containsExactly(
          new MealResponse("조식", List.of("흑미밥", "미역국 (5.6)"), "926.1 Kcal"));
      verify(redisRepository).save(RedisKeyType.MEAL_INFO, "20260810", response);
    }

    @Test
    @DisplayName("mealType이 지정되면 해당 급식만 걸러 반환한다")
    void filtersByMealType() {
      MealsResponse cached = new MealsResponse("20260810", List.of(
          new MealResponse("조식", List.of("흑미밥"), "900 Kcal"),
          new MealResponse("중식", List.of("돈까스"), "800 Kcal")));
      given(redisRepository.find(RedisKeyType.MEAL_INFO, "20260810", MealsResponse.class))
          .willReturn(cached);

      MealsResponse response = mealService.getMeals(DATE, MealType.LUNCH);

      assertThat(response.meals()).containsExactly(
          new MealResponse("중식", List.of("돈까스"), "800 Kcal"));
    }

    @Test
    @DisplayName("데이터가 없는 날은 빈 리스트를 반환한다")
    void returnsEmptyListWhenNoData() {
      given(redisRepository.find(RedisKeyType.MEAL_INFO, "20260805", MealsResponse.class))
          .willReturn(null);
      given(neisClient.fetch(
          eq("/mealServiceDietInfo"), any(), eq("mealServiceDietInfo"), eq(NeisMealRow.class)))
          .willReturn(List.of());

      MealsResponse response = mealService.getMeals(LocalDate.of(2026, 8, 5), null);

      assertThat(response.meals()).isEmpty();
    }
  }
}
