package com.remake.gone.outing.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.outing.dto.OutingActiveResponse;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingLocationRequest;
import com.remake.gone.outing.dto.OutingLocationsResponse;
import com.remake.gone.outing.dto.OutingRejectRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.enums.OutingQueryPeriod;
import com.remake.gone.outing.enums.OutingQueryStatus;
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
 * <p>#29에서 신청, #30에서 승인, #31에서 거절, #41에서 조회(본인 신청/배정/단건 상세), #43에서
 * 출발/도착 보고 엔드포인트를 구현했다.
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
   * 학생 본인이 승인된 외출증의 출발을 보고합니다. STUDENT 역할만 보고할 수 있으며, 본인이 그
   * 외출증의 학생인지는 서비스에서 소유권을 확인합니다(#43).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param code      출발을 보고할 외출증의 외부 식별자 코드
   * @param request   출발 시점의 좌표
   * @return 출발 처리된 외출증 정보
   */
  @PostMapping("/{code}/depart")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<OutingResponse> departOuting(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String code,
      @Valid @RequestBody OutingLocationRequest request
  ) {
    OutingResponse response = outingService.departOuting(
        principal.userId(), code, request, LocalDateTime.now(KST));
    String message = response.offSchedule()
        ? "예정된 시간 외에 출발이 기록되었습니다."
        : "출발이 기록되었습니다.";
    return ApiResponse.success(response, message);
  }

  /**
   * 학생 본인이 출발한 외출증의 도착을 보고합니다. STUDENT 역할만 보고할 수 있으며, 본인이 그
   * 외출증의 학생인지는 서비스에서 소유권을 확인합니다(#43).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param code      도착을 보고할 외출증의 외부 식별자 코드
   * @param request   도착 시점의 좌표
   * @return 도착 처리된 외출증 정보
   */
  @PostMapping("/{code}/return")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<OutingResponse> returnOuting(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String code,
      @Valid @RequestBody OutingLocationRequest request
  ) {
    OutingResponse response = outingService.returnOuting(
        principal.userId(), code, request, LocalDateTime.now(KST));
    String message = response.offSchedule()
        ? "예정된 시간 외에 도착이 기록되었습니다."
        : "도착이 기록되었습니다.";
    return ApiResponse.success(response, message);
  }

  /**
   * 학생 본인이 외출 중({@code DEPARTED}) 위치 핑을 전송합니다. STUDENT 역할만 전송할 수
   * 있으며, 본인이 그 외출증의 학생인지는 서비스에서 소유권을 확인합니다(#97).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param code      핑을 전송할 외출증의 외부 식별자 코드
   * @param request   현재 위치 좌표
   * @return 성공 여부만 담은 응답(매 핑마다 무거운 응답 불필요)
   */
  @PostMapping("/{code}/locations")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<Void> recordLocationPing(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String code,
      @Valid @RequestBody OutingLocationRequest request
  ) {
    outingService.recordLocationPing(
        principal.userId(), code, request, LocalDateTime.now(KST));
    return ApiResponse.success(null, "위치가 저장되었습니다.");
  }

  /**
   * 외출증의 위치/동선을 조회합니다. 담당 선생님 본인, 또는 {@code DISCIPLINE}/{@code ADMIN}
   * 역할 보유자만 조회할 수 있으며(전체 {@code TEACHER}는 아님), 소유권/세부 역할 판단은
   * 서비스에서 합니다(#97).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param code      조회할 외출증의 외부 식별자 코드
   * @return 출발 좌표 → 위치 핑(시간순) → 도착 좌표 순으로 합성된 동선
   */
  @GetMapping("/{code}/locations")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<OutingLocationsResponse> getOutingLocations(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String code
  ) {
    OutingLocationsResponse response =
        outingService.getOutingLocations(principal.userId(), code);
    return ApiResponse.success(response, "위치 조회에 성공했습니다.");
  }

  /**
   * 본인이 신청한 외출증을 조회합니다. STUDENT 역할만 조회할 수 있습니다(#41).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param period    조회 기간 프리셋(생략 시 {@code THIS_WEEK})
   * @param dateFrom  {@code period == CUSTOM}일 때의 시작일({@code yyyyMMdd})
   * @param dateTo    {@code period == CUSTOM}일 때의 종료일({@code yyyyMMdd})
   * @param status    걸러볼 상태(생략 시 전부 반환)
   * @param page      페이지 번호(0부터 시작, 생략 시 0)
   * @param size      페이지 크기(1~100, 생략 시 20)
   * @return 조건에 맞는 외출증의 페이지네이션된 목록
   */
  @GetMapping("/me/requests")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<PageResponse<OutingResponse>> getMyRequests(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(defaultValue = "THIS_WEEK") OutingQueryPeriod period,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateTo,
      @RequestParam(required = false) OutingQueryStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    LocalDateTime now = LocalDateTime.now(KST);
    PageResponse<OutingResponse> response = outingService.getMyRequests(
        principal.userId(), period, dateFrom, dateTo, status, page, size,
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
   * @param page      페이지 번호(0부터 시작, 생략 시 0)
   * @param size      페이지 크기(1~100, 생략 시 20)
   * @return 조건에 맞는 외출증의 페이지네이션된 목록
   */
  @GetMapping("/me/received")
  @PreAuthorize("hasRole('TEACHER')")
  public ApiResponse<PageResponse<OutingResponse>> getReceivedOutings(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(defaultValue = "THIS_WEEK") OutingQueryPeriod period,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateTo,
      @RequestParam(required = false) OutingQueryStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    LocalDateTime now = LocalDateTime.now(KST);
    PageResponse<OutingResponse> response = outingService.getReceivedOutings(
        principal.userId(), period, dateFrom, dateTo, status, page, size,
        now.toLocalDate(), now.toLocalTime());
    return ApiResponse.success(response, "외출증 목록을 조회했습니다.");
  }

  /**
   * 지금 외출 중(DEPARTED)인 학생 목록을 조회합니다. {@code DISCIPLINE}/{@code TEACHER}/
   * {@code ADMIN} 역할이 필요합니다(#96).
   *
   * @param page 페이지 번호(0부터 시작, 생략 시 0)
   * @param size 페이지 크기(1~100, 생략 시 20)
   * @return 지금 외출 중인 학생 목록의 페이지네이션된 결과({@code departedAt} 오름차순)
   */
  @GetMapping("/active")
  @PreAuthorize("hasAnyRole('DISCIPLINE', 'TEACHER', 'ADMIN')")
  public ApiResponse<PageResponse<OutingActiveResponse>> getActiveOutings(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    PageResponse<OutingActiveResponse> response = outingService.getActiveOutings(page, size);
    return ApiResponse.success(response, "현재 외출 중인 학생 목록입니다.");
  }

  /**
   * 특정 날짜(기본값 오늘)의 외출증 전체 현황을 조회합니다. {@code DISCIPLINE}/
   * {@code TEACHER}/{@code ADMIN} 역할이 필요합니다(#98).
   *
   * @param date   조회할 날짜(생략 시 오늘)
   * @param status 걸러볼 상태(생략 시 전부 반환)
   * @param page   페이지 번호(0부터 시작, 생략 시 0)
   * @param size   페이지 크기(1~100, 생략 시 20)
   * @return 조건에 맞는 외출증의 페이지네이션된 목록
   */
  @GetMapping
  @PreAuthorize("hasAnyRole('DISCIPLINE', 'TEACHER', 'ADMIN')")
  public ApiResponse<PageResponse<OutingResponse>> getDailyOverview(
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate date,
      @RequestParam(required = false) OutingQueryStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    LocalDateTime now = LocalDateTime.now(KST);
    PageResponse<OutingResponse> response = outingService.getDailyOverview(
        date, status, page, size, now.toLocalDate(), now.toLocalTime());
    return ApiResponse.success(response, "외출증 현황을 조회했습니다.");
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
