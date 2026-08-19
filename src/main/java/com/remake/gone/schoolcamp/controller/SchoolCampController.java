package com.remake.gone.schoolcamp.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.schoolcamp.dto.RegisterSchoolCampDatesRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampApplicationResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampApplyRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.service.SchoolCampService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스쿨캠핑(SchoolCamp) 세션 등록/캘린더 조회/신청 API.
 */
@RestController
@RequestMapping("/api/v1/school-camps")
@RequiredArgsConstructor
public class SchoolCampController {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final SchoolCampService schoolCampService;

  /**
   * 다음 달 스쿨캠핑 가능 날짜를 관리자가 일괄 등록합니다.
   *
   * @param request 등록할 날짜 목록
   * @return 등록된 세션 목록
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<List<SchoolCampSessionResponse>> registerCampDates(
      @Valid @RequestBody RegisterSchoolCampDatesRequest request
  ) {
    List<SchoolCampSessionResponse> response =
        schoolCampService.registerCampDates(request.campDates());
    return ApiResponse.success(response, "스쿨캠핑 일정이 등록되었습니다.");
  }

  /**
   * 특정 달의 스쿨캠핑 캘린더(날짜별 점유 상태)를 조회합니다. 인증된 사용자 누구나 조회할 수
   * 있습니다.
   *
   * @param month 조회할 달({@code yyyyMM} 형식)
   * @return 그 달의 세션별 캘린더 정보
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<List<SchoolCampCalendarResponse>> getCalendar(
      @RequestParam @DateTimeFormat(pattern = "yyyyMM") YearMonth month
  ) {
    List<SchoolCampCalendarResponse> response = schoolCampService.getCalendar(month);
    return ApiResponse.success(response, "스쿨캠핑 일정을 조회했습니다.");
  }

  /**
   * 대표 학생 1명이 팀(본인 포함 최대 8명)을 한 번에 등록해 스쿨캠핑에 신청합니다.
   * {@code STUDENT} 역할만 신청할 수 있습니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param sessionId 신청할 세션의 PK
   * @param request   신청 요청 정보
   * @return 생성된 신청 정보
   */
  @PostMapping("/{sessionId}/applications")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<SchoolCampApplicationResponse> applyToCamp(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long sessionId,
      @Valid @RequestBody SchoolCampApplyRequest request
  ) {
    SchoolCampApplicationResponse response = schoolCampService.applyToCamp(
        principal.userId(), sessionId, request, LocalDateTime.now(KST));
    return ApiResponse.success(response, "스쿨캠핑 신청이 완료되었습니다.");
  }
}
