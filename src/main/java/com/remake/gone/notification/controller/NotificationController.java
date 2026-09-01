package com.remake.gone.notification.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.notification.dto.NotificationResponse;
import com.remake.gone.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림(Notification) 도메인 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;

  /**
   * 현재 사용자가 받은 알림을 최신순으로 조회합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param page      페이지 번호(0부터 시작, 생략 시 0)
   * @param size      페이지 크기(1~100, 생략 시 20)
   * @return 페이지네이션된 알림 목록
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<PageResponse<NotificationResponse>> getNotifications(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    PageResponse<NotificationResponse> response =
        notificationService.getNotifications(principal.userId(), page, size);
    return ApiResponse.success(response, "알림 목록을 조회했습니다.");
  }
}
