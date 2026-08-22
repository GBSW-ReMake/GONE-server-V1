package com.remake.gone.conduct.repository;

import com.remake.gone.conduct.entity.ConductCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link ConductCategory} 리포지토리. */
public interface ConductCategoryRepository extends JpaRepository<ConductCategory, Long> {

  /**
   * 활성 카테고리를 {@code id} 오름차순으로 조회합니다.
   *
   * @return {@code active = true}인 카테고리 목록
   */
  List<ConductCategory> findByActiveTrueOrderByIdAsc();
}
