package com.remake.gone.schoolcamp.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.schoolcamp.dto.RegisterSchoolCampDatesRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampApplicationResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampApplyRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampMyParticipationResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampMyParticipationSummaryResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampWaitlistResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampWaitlistStatusResponse;
import com.remake.gone.schoolcamp.service.SchoolCampService;
import com.remake.gone.schoolcamp.service.SchoolCampWaitlistService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 스쿨캠핑(SchoolCamp) 세션 등록/캘린더 조회/신청/취소/수정 API.
 */
@RestController
@RequestMapping("/api/v1/school-camps")
@RequiredArgsConstructor
public class SchoolCampController {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final SchoolCampService schoolCampService;
  private final SchoolCampWaitlistService schoolCampWaitlistService;

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
   * 본인(대표/팀원/담당 선생님 중 하나)이 관련된 스쿨캠핑 이력을 목록으로 조회합니다(#69).
   * 취소된 신청도 포함하며(응답의 {@code cancelledAt}으로 구분), 팀원 전체는 담지 않는다 —
   * 상세는 {@link #getMyParticipationDetail}로 따로 조회한다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param month     범위를 한정할 달({@code yyyyMM}). 생략하면 전체 이력
   * @param page      페이지 번호(0부터 시작, 생략 시 0)
   * @param size      페이지 크기(1~100, 생략 시 20)
   * @return 페이지네이션된 참여 내역 요약 목록
   */
  @GetMapping("/me")
  @PreAuthorize("hasAnyRole('STUDENT', 'TEACHER')")
  public ApiResponse<PageResponse<SchoolCampMyParticipationSummaryResponse>> getMyParticipations(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMM") YearMonth month,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    PageResponse<SchoolCampMyParticipationSummaryResponse> response =
        schoolCampService.getMyParticipations(principal.userId(), month, page, size);
    return ApiResponse.success(response, "스쿨캠핑 참여 내역을 조회했습니다.");
  }

  /**
   * 본인이 참여한 신청 1건의 상세(담당 선생님/팀원 전체)를 조회합니다(#69). 취소된
   * 신청도 조회할 수 있다. 본인이 그 신청의 참여자(대표, 팀원, 또는 담당 선생님)가
   * 아니면 거부한다.
   *
   * @param principal     인증 필터가 Access Token에서 추출한 현재 사용자
   * @param applicationId 조회할 신청의 PK
   * @return 팀원 전체를 포함한 참여 내역 상세
   */
  @GetMapping("/applications/{applicationId}")
  @PreAuthorize("hasAnyRole('STUDENT', 'TEACHER')")
  public ApiResponse<SchoolCampMyParticipationResponse> getMyParticipationDetail(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long applicationId
  ) {
    SchoolCampMyParticipationResponse response =
        schoolCampService.getMyParticipationDetail(principal.userId(), applicationId);
    return ApiResponse.success(response, "스쿨캠핑 참여 상세를 조회했습니다.");
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

  /**
   * 본인 신청을 취소합니다. 소유권 확인과 당일 취소 금지는 서비스에서 처리합니다.
   *
   * @param principal     인증 필터가 Access Token에서 추출한 현재 사용자
   * @param applicationId 취소할 신청의 PK
   * @return 데이터 없는 성공 응답
   */
  @DeleteMapping("/applications/{applicationId}")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<Void> cancelApplication(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long applicationId
  ) {
    schoolCampService.cancelApplication(
        principal.userId(), applicationId, LocalDateTime.now(KST));
    return ApiResponse.success(null, "신청이 취소되었습니다.");
  }

  /**
   * 본인 신청의 담당 선생님/팀원 정보를 수정합니다. 전체 교체 방식이라, 바뀌지 않은 기존
   * 팀원도 포함해 원하는 최종 팀원 목록 전체를 보내야 합니다. 소유권 확인은 서비스에서
   * 처리합니다.
   *
   * @param principal     인증 필터가 Access Token에서 추출한 현재 사용자
   * @param applicationId 수정할 신청의 PK
   * @param request       새 담당 선생님/팀원 스냅샷
   * @return 갱신된 신청 정보
   */
  @PatchMapping("/applications/{applicationId}")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<SchoolCampApplicationResponse> updateApplication(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long applicationId,
      @Valid @RequestBody SchoolCampApplyRequest request
  ) {
    SchoolCampApplicationResponse response =
        schoolCampService.updateApplication(principal.userId(), applicationId, request);
    return ApiResponse.success(response, "신청 정보가 수정되었습니다.");
  }

  /**
   * 이번 달 "자리나면 알림받기"를 등록합니다. 파라미터 없이 요청 시점의 "이번 달"만
   * 다룹니다(#83). {@code STUDENT} 역할만 등록할 수 있습니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 등록된 대기 정보
   */
  @PostMapping("/waitlist")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<SchoolCampWaitlistResponse> registerWaitlist(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    SchoolCampWaitlistResponse response =
        schoolCampWaitlistService.register(principal.userId(), LocalDateTime.now(KST));
    return ApiResponse.success(response, "자리나면 알림받기를 등록했습니다.");
  }

  /**
   * 이번 달 "자리나면 알림받기"를 취소합니다(#83).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 데이터 없는 성공 응답
   */
  @DeleteMapping("/waitlist")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<Void> cancelWaitlist(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    schoolCampWaitlistService.cancel(principal.userId(), LocalDateTime.now(KST));
    return ApiResponse.success(null, "자리나면 알림받기를 취소했습니다.");
  }

  /**
   * 이번 달 "자리나면 알림받기" 등록 상태를 조회합니다(#83). 캘린더에서 버튼을 "등록됨"/
   * "등록 안 됨" 어느 상태로 그릴지 판단하는 용도입니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 등록 상태
   */
  @GetMapping("/waitlist/me")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<SchoolCampWaitlistStatusResponse> getWaitlistStatus(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    SchoolCampWaitlistStatusResponse response =
        schoolCampWaitlistService.getStatus(principal.userId(), LocalDateTime.now(KST));
    return ApiResponse.success(response, "대기 등록 상태를 조회했습니다.");
  }
}
