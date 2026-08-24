package com.remake.gone.conduct.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.dto.ConductGrantRequest;
import com.remake.gone.conduct.dto.ConductRecordResponse;
import com.remake.gone.conduct.service.ConductService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상/벌점(Conduct) 도메인 API 컨트롤러.
 *
 * <p>#92에서 카테고리 목록 조회, #94에서 상/벌점 부여 엔드포인트를 구현했다.
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

  /**
   * 교사가 학생에게 상/벌점을 부여합니다.
   *
   * @param principal 인증된 교사 정보
   * @param request   부여 요청 정보
   * @return 생성된 상/벌점 기록
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('TEACHER')")
  public ApiResponse<ConductRecordResponse> grantConduct(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody ConductGrantRequest request) {
    return ApiResponse.success(
        conductService.grantConduct(principal.userId(), request),
        "상/벌점이 부여되었습니다.");
  }
}
