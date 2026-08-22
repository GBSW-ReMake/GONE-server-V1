package com.remake.gone.conduct.service;

import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상/벌점(Conduct) 도메인 서비스. */
@Service
@RequiredArgsConstructor
public class ConductService {

  private final ConductCategoryRepository conductCategoryRepository;

  /**
   * 활성 카테고리 목록을 조회합니다.
   *
   * @return {@code active = true}인 카테고리를 {@code id} 오름차순으로 정렬한 목록
   */
  @Transactional(readOnly = true)
  public List<ConductCategoryResponse> getCategories() {
    return conductCategoryRepository.findByActiveTrueOrderByIdAsc().stream()
        .map(ConductCategoryResponse::from)
        .toList();
  }
}
