package com.remake.gone.outing.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.service.OutingService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * 외출(Outing) 도메인 API 컨트롤러.
 *
 * <p>#29에서 신청, #30에서 승인 엔드포인트를 구현했다. 거절/출발/도착 등은 후속 이슈
 * (#31/...)에서 추가된다.
 */
@RestController
@RequestMapping("/api/v1/outings")
@RequiredArgsConstructor
public class OutingController {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final OutingService outingService;

  /**
   * 외출증을 신청합니다. Access Token 인증이 필요하며, STUDENT 역할만 신청할 수 있습니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param request   신청 요청 정보
   * @return 생성된 외출증 정보
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<OutingResponse> applyOuting(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody OutingApplyRequest request
  ) {
    LocalDateTime now = LocalDateTime.now(KST);
    OutingResponse response = outingService.applyOuting(
        principal.userId(), request, now.toLocalDate(), now.toLocalTime());
    return ApiResponse.success(response, "외출증 신청이 접수되었습니다.");
  }

  /**
   * 담당 선생님이 학생의 외출증 신청을 승인합니다. TEACHER 역할이 필요하며, 본인이 지정된
   * 담당 선생님인지는 서비스에서 소유권을 확인합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param code      승인할 외출증의 외부 식별자 코드
   * @return 승인된 외출증 정보
   */
  @PatchMapping("/{code}/approve")
  @PreAuthorize("hasRole('TEACHER')")
  public ApiResponse<OutingResponse> approveOuting(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String code
  ) {
    OutingResponse response =
        outingService.approveOuting(principal.userId(), code, LocalDateTime.now(KST));
    return ApiResponse.success(response, "외출증을 승인했습니다.");
  }
}
