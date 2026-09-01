package com.remake.gone.conduct.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.conduct.dto.ConductRequestCreateRequest;
import com.remake.gone.conduct.dto.ConductRequestResponse;
import com.remake.gone.conduct.service.ConductRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상/벌점 요청(ConductRequest) API 컨트롤러.
 *
 * <p>#122에서 요청 생성·취소 엔드포인트를 구현했다.
 */
@RestController
@RequestMapping("/api/v1/conduct-requests")
@RequiredArgsConstructor
public class ConductRequestController {

  private final ConductRequestService conductRequestService;

  /**
   * 선도부가 상/벌점 부여를 요청합니다.
   *
   * @param principal 인증된 선도부 정보
   * @param request   요청 생성 정보
   * @return 생성된 상/벌점 요청
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('DISCIPLINE')")
  public ApiResponse<ConductRequestResponse> createRequest(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody ConductRequestCreateRequest request) {
    return ApiResponse.success(
        conductRequestService.createRequest(principal.userId(), request),
        "상/벌점 요청이 등록되었습니다.");
  }

  /**
   * 선도부가 본인이 등록한 상/벌점 요청을 취소합니다.
   *
   * <p>PENDING 상태의 요청만 취소할 수 있습니다.
   *
   * @param principal 인증된 선도부 정보
   * @param id        취소할 요청 ID
   * @return 취소된 상/벌점 요청
   */
  @PatchMapping("/{id}/cancel")
  @PreAuthorize("hasRole('DISCIPLINE')")
  public ApiResponse<ConductRequestResponse> cancelRequest(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long id) {
    return ApiResponse.success(
        conductRequestService.cancelRequest(principal.userId(), id),
        "상/벌점 요청이 취소되었습니다.");
  }
}
