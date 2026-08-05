package com.remake.gone.timetable.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.timetable.dto.TimetableResponse;
import com.remake.gone.timetable.service.TimetableService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NEIS 시간표 조회 API 컨트롤러.
 *
 * <p>학년/반을 요청 파라미터로 받지 않고 로그인한 본인 학급을 자동으로 조회하므로 Access
 * Token 인증이 필요하다({@code SecurityConfig} 참고).
 */
@RestController
@RequestMapping("/api/v1/timetables")
@RequiredArgsConstructor
public class TimetableController {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final TimetableService timetableService;

  /**
   * 로그인한 본인 학급의 시간표를 조회합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param date      조회할 날짜(yyyyMMdd). 생략 시 오늘(KST)
   * @return 시간표 조회 응답
   */
  @GetMapping
  public ApiResponse<TimetableResponse> getMyTimetable(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate date
  ) {
    LocalDate targetDate = date != null ? date : LocalDate.now(KST);
    TimetableResponse response = timetableService.getMyTimetable(principal.userId(), targetDate);
    return ApiResponse.success(response, "시간표를 조회했습니다.");
  }
}
