package com.remake.gone.conduct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.enums.ConductType;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ConductService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ConductServiceTest {

  @Mock
  private ConductCategoryRepository conductCategoryRepository;

  @InjectMocks
  private ConductService conductService;

  @Nested
  @DisplayName("getCategories")
  class GetCategories {

    @Test
    @DisplayName("활성 카테고리 목록을 id·label·type·points가 정확히 매핑된 DTO로 변환해 반환한다")
    void returnsActiveCategoriesAsMappedDto() {
      ConductCategory merit = ConductCategory.builder()
          .id(1L)
          .label("봉사활동으로 교내 청소를 열심히 한 학생")
          .type(ConductType.MERIT)
          .points(2)
          .build();
      ConductCategory demerit = ConductCategory.builder()
          .id(19L)
          .label("용의 규정을 위반한 학생(염색)")
          .type(ConductType.DEMERIT)
          .points(-5)
          .build();
      given(conductCategoryRepository.findByActiveTrueOrderByIdAsc())
          .willReturn(List.of(merit, demerit));

      List<ConductCategoryResponse> result = conductService.getCategories();

      assertThat(result).hasSize(2);
      assertThat(result.get(0).id()).isEqualTo(1L);
      assertThat(result.get(0).label()).isEqualTo("봉사활동으로 교내 청소를 열심히 한 학생");
      assertThat(result.get(0).type()).isEqualTo(ConductType.MERIT);
      assertThat(result.get(0).points()).isEqualTo(2);
      assertThat(result.get(1).id()).isEqualTo(19L);
      assertThat(result.get(1).label()).isEqualTo("용의 규정을 위반한 학생(염색)");
      assertThat(result.get(1).type()).isEqualTo(ConductType.DEMERIT);
      assertThat(result.get(1).points()).isEqualTo(-5);
    }

    @Test
    @DisplayName("활성 카테고리가 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoActiveCategories() {
      given(conductCategoryRepository.findByActiveTrueOrderByIdAsc())
          .willReturn(List.of());

      List<ConductCategoryResponse> result = conductService.getCategories();

      assertThat(result).isEmpty();
    }
  }
}
