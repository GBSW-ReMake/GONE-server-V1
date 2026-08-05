package com.remake.gone.meal.enums;

/**
 * 급식 구분(조식/중식/석식).
 *
 * <p>NEIS는 이 구분을 한글 식사명({@code MMEAL_SC_NM})으로 내려주므로, 그 문자열과의 매칭에
 * {@link #neisName}을 사용한다.
 */
public enum MealType {
  BREAKFAST("조식"),
  LUNCH("중식"),
  DINNER("석식");

  private final String neisName;

  MealType(String neisName) {
    this.neisName = neisName;
  }

  /**
   * NEIS 응답의 식사명이 이 급식 구분과 같은지 확인한다.
   *
   * @param neisMealTypeName NEIS 응답의 {@code MMEAL_SC_NM} 값
   * @return 같은 급식 구분이면 {@code true}
   */
  public boolean matches(String neisMealTypeName) {
    return neisName.equals(neisMealTypeName);
  }
}
