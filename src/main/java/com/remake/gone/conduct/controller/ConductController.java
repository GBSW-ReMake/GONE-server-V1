package com.remake.gone.conduct.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.conduct.dto.ConductAmendRequest;
import com.remake.gone.conduct.dto.ConductCancelRequest;
import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.dto.ConductGrantRequest;
import com.remake.gone.conduct.dto.ConductRecordResponse;
import com.remake.gone.conduct.dto.ConductStudentRecordResponse;
import com.remake.gone.conduct.dto.ConductSummaryResponse;
import com.remake.gone.conduct.enums.ConductType;
import com.remake.gone.conduct.service.ConductService;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * 상/벌점(Conduct) 도메인 API 컨트롤러.
 *
 * <p>#92에서 카테고리 목록 조회, #94에서 상/벌점 부여, #107에서 정정·취소,
 * #111에서 학생 본인 조회 엔드포인트를 구현했다.
 */
@RestController
@RequestMapping("/api/v1/conduct-records")
@RequiredArgsConstructor
public class ConductController {

  private final ConductService conductService;

  /**
   * 활성 카테고리 목록을 조회합니다. 인증된 사용자 전체가 접근할 수 있습니다.
   *
   * @return 활성 카테고리 목록
   */
  @GetMapping("/categories")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<List<ConductCategoryResponse>> getCategories() {
    return ApiResponse.success(conductService.getCategories(), "카테고리 목록을 조회했습니다.");
  }

  /**
   * 교사가 학생에게 상/벌점을 부여합니다.
   *
   * @param principal 인증된 교사 정보
   * @param request   부여 요청 정보
   * @return 생성된 상/벌점 기록
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('TEACHER')")
  public ApiResponse<ConductRecordResponse> grantConduct(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody ConductGrantRequest request) {
    return ApiResponse.success(
        conductService.grantConduct(principal.userId(), request),
        "상/벌점이 부여되었습니다.");
  }

  /**
   * 상/벌점 기록을 정정합니다.
   *
   * <p>TEACHER는 본인이 부여한 기록만 정정할 수 있습니다.
   *
   * @param principal 인증된 사용자 정보
   * @param id        정정할 기록 ID
   * @param request   정정 요청 정보
   * @return 정정된 상/벌점 기록
   */
  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
  public ApiResponse<ConductRecordResponse> amendConduct(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long id,
      @Valid @RequestBody ConductAmendRequest request) {
    return ApiResponse.success(
        conductService.amendConduct(principal.userId(), id, request),
        "상/벌점 기록이 정정되었습니다.");
  }

  /**
   * 상/벌점 기록을 취소합니다.
   *
   * <p>TEACHER는 본인이 부여한 기록만 취소할 수 있습니다. 취소는 되돌릴 수 없습니다.
   *
   * @param principal 인증된 사용자 정보
   * @param id        취소할 기록 ID
   * @param request   취소 요청 정보
   * @return 취소된 상/벌점 기록
   */
  @PatchMapping("/{id}/cancel")
  @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
  public ApiResponse<ConductRecordResponse> cancelConduct(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long id,
      @Valid @RequestBody ConductCancelRequest request) {
    return ApiResponse.success(
        conductService.cancelConduct(principal.userId(), id, request),
        "상/벌점 기록이 취소되었습니다.");
  }

  /**
   * 학생 본인의 누적 상/벌점 요약을 조회합니다.
   *
   * <p>전체 기간 기준 총 상점·벌점·순 점수와 벌점 임계치 초과 여부를 반환합니다.
   *
   * @param principal 인증된 학생 정보
   * @return 누적 점수 요약
   */
  @GetMapping("/me/summary")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<ConductSummaryResponse> getStudentSummary(
      @AuthenticationPrincipal UserPrincipal principal) {
    return ApiResponse.success(
        conductService.getStudentSummary(principal.userId()),
        "누적 점수를 조회했습니다.");
  }

  /**
   * 학생 본인의 상/벌점 이력을 조회합니다.
   *
   * <p>취소된 기록도 포함하며, {@code type}·기간 필터와 페이지네이션을 지원합니다.
   *
   * @param principal 인증된 학생 정보
   * @param type      종류 필터({@code MERIT}/{@code DEMERIT}, 생략 시 전체)
   * @param dateFrom  조회 시작일({@code yyyyMMdd}, {@code dateTo}와 함께 써야 함)
   * @param dateTo    조회 종료일({@code yyyyMMdd}, {@code dateFrom}과 함께 써야 함)
   * @param page      페이지 번호(기본값 {@code 0})
   * @param size      페이지 크기(기본값 {@code 20}, 1~100)
   * @return 페이지네이션된 이력 목록
   */
  @GetMapping("/me")
  @PreAuthorize("hasRole('STUDENT')")
  public ApiResponse<PageResponse<ConductStudentRecordResponse>> getStudentRecords(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(required = false) ConductType type,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyyMMdd") LocalDate dateTo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(
        conductService.getStudentRecords(
            principal.userId(), type, dateFrom, dateTo, page, size),
        "상/벌점 이력을 조회했습니다.");
  }
}
