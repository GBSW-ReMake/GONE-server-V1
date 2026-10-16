package com.remake.gone.notification.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.notification.dto.NotificationResponse;
import com.remake.gone.notification.dto.RegisterDeviceTokenRequest;
import com.remake.gone.notification.dto.UnreadNotificationCountResponse;
import com.remake.gone.notification.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림(Notification) 도메인 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Validated
public class NotificationController {

  private final NotificationService notificationService;

  /**
   * 현재 기기의 FCM 디바이스 토큰을 등록하거나 갱신합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param request 클라이언트가 Firebase에서 발급받은 FCM 토큰
   * @return 디바이스 토큰 등록 결과
   */
  @PutMapping("/device-token")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<Void> registerDeviceToken(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody RegisterDeviceTokenRequest request
  ) {
    notificationService.registerDeviceToken(principal.userId(), request.fcmToken());
    return ApiResponse.success(null, "디바이스 토큰이 등록되었습니다.");
  }

  /**
   * 현재 사용자의 FCM 디바이스 토큰을 삭제합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 디바이스 토큰 삭제 결과
   */
  @DeleteMapping("/device-token")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<Void> deleteDeviceToken(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    notificationService.deleteDeviceToken(principal.userId());
    return ApiResponse.success(null, "디바이스 토큰이 삭제되었습니다.");
  }

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

  /**
   * 현재 사용자가 선택한 알림을 읽음 처리합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param id 읽음 처리할 알림 ID
   * @return 읽음 처리 결과
   */
  @PatchMapping("/{id}/read")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<Void> markAsRead(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable @Positive(message = "알림 ID는 양수여야 합니다.") Long id
  ) {
    notificationService.markAsRead(principal.userId(), id);
    return ApiResponse.success(null, "알림을 읽음 처리했습니다.");
  }

  /**
   * 현재 사용자가 받은 읽지 않은 알림을 모두 읽음 처리합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 전체 읽음 처리 결과
   */
  @PatchMapping("/read-all")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<Void> markAllAsRead(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    notificationService.markAllAsRead(principal.userId());
    return ApiResponse.success(null, "모든 알림을 읽음 처리했습니다.");
  }

  /**
   * 현재 사용자가 읽지 않은 알림 개수를 조회합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 안 읽은 알림 개수
   */
  @GetMapping("/unread-count")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<UnreadNotificationCountResponse> getUnreadCount(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    UnreadNotificationCountResponse response =
        notificationService.getUnreadCount(principal.userId());
    return ApiResponse.success(response, "안 읽은 알림 개수를 조회했습니다.");
  }
}
