package com.remake.gone.conduct.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.service.ConductService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상/벌점(Conduct) 도메인 API 컨트롤러.
 *
 * <p>#92에서 카테고리 목록 조회 엔드포인트를 구현했다.
 */
@RestController
@RequestMapping("/api/v1/conduct-records")
@RequiredArgsConstructor
public class ConductController {

  private final ConductService conductService;

  /**
   * 활성 카테고리 목록을 조회합니다. 인증된 사용자 전체가 접근할 수 있습니다.
   *
   * @return 활성 카테고리 목록
   */
  @GetMapping("/categories")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<List<ConductCategoryResponse>> getCategories() {
    return ApiResponse.success(conductService.getCategories(), "카테고리 목록을 조회했습니다.");
  }
}
