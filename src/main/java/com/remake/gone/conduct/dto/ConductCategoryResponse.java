package com.remake.gone.conduct.dto;

import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.enums.ConductType;

/**
 * 카테고리 목록 조회 응답 DTO.
 *
 * @param id     카테고리 식별자. 부여/정정 요청의 {@code categoryId}로 그대로 사용한다.
 * @param label  부여 화면에 표시하는 사유 문구
 * @param type   상점({@code MERIT}) 또는 벌점({@code DEMERIT})
 * @param points 고정 점수(부호 포함)
 */
public record ConductCategoryResponse(Long id, String label, ConductType type, int points) {

  /**
   * {@link ConductCategory} 엔티티를 응답 DTO로 변환합니다.
   *
   * @param category 변환 대상 카테고리 엔티티
   * @return 변환된 {@link ConductCategoryResponse}
   */
  public static ConductCategoryResponse from(ConductCategory category) {
    return new ConductCategoryResponse(
        category.getId(),
        category.getLabel(),
        category.getType(),
        category.getPoints()
    );
  }
}
