package com.remake.gone.meal.service;

import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.meal.dto.MealResponse;
import com.remake.gone.meal.dto.MealsResponse;
import com.remake.gone.meal.enums.MealType;
import com.remake.gone.neis.NeisClient;
import com.remake.gone.neis.dto.NeisMealRow;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * NEIS 급식 정보를 조회하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class MealService {

  private static final DateTimeFormatter YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final NeisClient neisClient;
  private final RedisRepository redisRepository;

  /**
   * 특정 날짜의 급식 정보를 조회한다.
   *
   * <p>하루치 전체를 항상 캐싱하고, {@code mealType}이 지정되면 캐시(또는 방금 조회한 결과)에서
   * 걸러낸다 — 급식 종류별로 캐시를 따로 두지 않아도 되게 하기 위함이다.
   *
   * @param date     조회할 날짜
   * @param mealType 걸러낼 급식 구분. {@code null}이면 그날 전체를 반환
   * @return 급식 조회 응답. 데이터가 없는 날(주말/방학 등)은 빈 리스트를 담아 정상 응답한다
   */
  public MealsResponse getMeals(LocalDate date, MealType mealType) {
    String ymd = date.format(YMD_FORMATTER);
    MealsResponse response =
        redisRepository.find(RedisKeyType.MEAL_INFO, ymd, MealsResponse.class);
    if (response == null) {
      response = fetchAndCache(ymd);
    }
    return mealType == null ? response : filterByType(response, mealType);
  }

  private MealsResponse fetchAndCache(String ymd) {
    List<NeisMealRow> rows = neisClient.fetch(
        "/mealServiceDietInfo",
        Map.of("MLSV_YMD", ymd),
        "mealServiceDietInfo",
        NeisMealRow.class
    );
    List<MealResponse> meals = rows.stream()
        .map(row -> new MealResponse(
            row.mealTypeName(), splitDishes(row.dishName()), row.calorie()))
        .toList();
    MealsResponse response = new MealsResponse(ymd, meals);
    redisRepository.save(RedisKeyType.MEAL_INFO, ymd, response);
    return response;
  }

  private MealsResponse filterByType(MealsResponse response, MealType mealType) {
    List<MealResponse> filtered = response.meals().stream()
        .filter(meal -> mealType.matches(meal.mealType()))
        .toList();
    return new MealsResponse(response.date(), filtered);
  }

  private List<String> splitDishes(String dishName) {
    return Arrays.stream(dishName.split("<br/>"))
        .map(String::trim)
        .filter(dish -> !dish.isEmpty())
        .toList();
  }
}
