package com.remake.gone.outing.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingRejectRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.enums.OutingQueryPeriod;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.service.OutingService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 외출(Outing) 도메인 API 컨트롤러.
 *
 * <p>#29에서 신청, #30에서 승인, #31에서 거절, #41에서 조회(본인 신청/배정/단건 상세)
 * 엔드포인트를 구현했다. 출발/도착 등은 후속 이슈에서 추가된다.
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

  /**
   * 담당 선생님이 학생의 외출증 신청을 거절합니다. TEACHER 역할이 필요하며, 본인이 지정된
   * 담당 선생님인지는 서비스에서 소유권을 확인합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param code      거절할 외출증의 외부 식별자 코드
   * @param request   거절 사유를 담은 요청
   * @return 거절된 외출증 정보
   */
  @PatchMapping("/{code}/reject")
  @PreAuthorize("hasRole('TEACHER')")
  public ApiResponse<OutingResponse> rejectOuting(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String code,
      @Valid @RequestBody OutingRejectRequest request
  ) {
    OutingResponse response = outingService.rejectOuting(
        principal.userId(), code, request.rejectedReason(), LocalDateTime.now(KST));
    return ApiResponse.success(response, "외출증을 거절했습니다.");
  }

  /**
   * 본인이 신청한 외출증을 조회합니다. STUDENT 역할만 조회할 수 있습니다(#41).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param period    조회 기간 프리셋(생략 시 {@code THIS_WEEK})
   * @param dateFrom  {@code period == CUSTOM}일 때의 시작일({@code yyyyMMdd})
   * @param dateTo    {@code period == CUSTOM}일 때의 종료일({@code yyyyMMdd})
   * @param status    걸러볼 상태(생략 시 전부 반환)
   * @return 조건에 맞는 외출증 목록
   */
  @GetMapping("/me/requests")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<List<OutingResponse>> getMyRequests(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(defaultValue = "THIS_WEEK") OutingQueryPeriod period,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateTo,
      @RequestParam(required = false) OutingStatus status
  ) {
    LocalDateTime now = LocalDateTime.now(KST);
    List<OutingResponse> response = outingService.getMyRequests(
        principal.userId(), period, dateFrom, dateTo, status,
        now.toLocalDate(), now.toLocalTime());
    return ApiResponse.success(response, "외출증 목록을 조회했습니다.");
  }

  /**
   * 담당 선생님으로 지정된 외출증을 조회합니다. TEACHER 역할만 조회할 수 있습니다(#41).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param period    조회 기간 프리셋(생략 시 {@code THIS_WEEK})
   * @param dateFrom  {@code period == CUSTOM}일 때의 시작일({@code yyyyMMdd})
   * @param dateTo    {@code period == CUSTOM}일 때의 종료일({@code yyyyMMdd})
   * @param status    걸러볼 상태(생략 시 전부 반환)
   * @return 조건에 맞는 외출증 목록
   */
  @GetMapping("/me/received")
  @PreAuthorize("hasRole('TEACHER')")
  public ApiResponse<List<OutingResponse>> getReceivedOutings(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(defaultValue = "THIS_WEEK") OutingQueryPeriod period,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateTo,
      @RequestParam(required = false) OutingStatus status
  ) {
    LocalDateTime now = LocalDateTime.now(KST);
    List<OutingResponse> response = outingService.getReceivedOutings(
        principal.userId(), period, dateFrom, dateTo, status,
        now.toLocalDate(), now.toLocalTime());
    return ApiResponse.success(response, "외출증 목록을 조회했습니다.");
  }

  /**
   * 외출증 단건을 상세 조회합니다. 신청 학생 본인, 지정된 담당 선생님, 또는
   * {@code DISCIPLINE}/{@code ADMIN} 역할 보유자만 조회할 수 있으며, 소유권/세부 역할 판단은
   * 서비스에서 합니다(#41).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param code      조회할 외출증의 외부 식별자 코드
   * @return 외출증 상세 정보
   */
  @GetMapping("/{code}")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<OutingResponse> getOutingDetail(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String code
  ) {
    LocalDateTime now = LocalDateTime.now(KST);
    OutingResponse response = outingService.getOutingDetail(
        principal.userId(), code, now.toLocalDate(), now.toLocalTime());
    return ApiResponse.success(response, "외출증을 조회했습니다.");
  }
}
