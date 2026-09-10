package com.remake.gone.conduct.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.conduct.dto.ConductRequestApproveRequest;
import com.remake.gone.conduct.dto.ConductRequestCreateRequest;
import com.remake.gone.conduct.dto.ConductRequestResponse;
import com.remake.gone.conduct.enums.ConductRequestStatus;
import com.remake.gone.conduct.service.ConductRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상/벌점 요청(ConductRequest) API 컨트롤러.
 *
 * <p>#122에서 요청 생성·취소, #157에서 조회·승인·거절 엔드포인트를 구현했다.
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
   * 상/벌점 요청 목록을 조회합니다.
   *
   * <p>역할에 따라 조회 범위가 다릅니다. DISCIPLINE은 본인이 생성한 요청, TEACHER는 본인에게
   * 배정된 요청, ADMIN은 전체 요청을 조회합니다.
   *
   * @param principal 인증된 사용자 정보
   * @param status    상태 필터 (생략 시 전체)
   * @param page      페이지 번호 (0-based, 기본 0)
   * @param size      페이지 크기 (기본 20)
   * @return 페이지네이션된 요청 목록
   */
  @GetMapping
  @PreAuthorize("hasAnyRole('DISCIPLINE','TEACHER','ADMIN')")
  public ApiResponse<PageResponse<ConductRequestResponse>> getRequests(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) ConductRequestStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(
        conductRequestService.getRequests(principal.userId(), status, page, size),
        null);
  }

  /**
   * 담당자가 상/벌점 요청을 승인합니다.
   *
   * <p>TEACHER는 본인에게 배정된 요청만 승인할 수 있습니다. 승인 시 {@code ConductRecord}가
   * 생성됩니다.
   *
   * @param principal      인증된 담당자 정보
   * @param id             승인할 요청 ID
   * @param approveRequest 카테고리·사유 오버라이드 (선택)
   * @return 승인된 상/벌점 요청
   */
  @PatchMapping("/{id}/approve")
  @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
  public ApiResponse<ConductRequestResponse> approveRequest(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long id,
      @RequestBody(required = false) ConductRequestApproveRequest approveRequest) {
    return ApiResponse.success(
        conductRequestService.approveRequest(principal.userId(), id, approveRequest),
        "상/벌점 요청이 승인되었습니다.");
  }

  /**
   * 담당자가 상/벌점 요청을 거절합니다.
   *
   * <p>TEACHER는 본인에게 배정된 요청만 거절할 수 있습니다.
   *
   * @param principal 인증된 담당자 정보
   * @param id        거절할 요청 ID
   * @return 거절된 상/벌점 요청
   */
  @PatchMapping("/{id}/reject")
  @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
  public ApiResponse<ConductRequestResponse> rejectRequest(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long id) {
    return ApiResponse.success(
        conductRequestService.rejectRequest(principal.userId(), id),
        "상/벌점 요청이 거절되었습니다.");
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
