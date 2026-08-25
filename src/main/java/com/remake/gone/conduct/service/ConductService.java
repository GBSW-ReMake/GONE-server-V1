package com.remake.gone.conduct.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.conduct.config.ConductProperties;
import com.remake.gone.conduct.dto.ConductAmendRequest;
import com.remake.gone.conduct.dto.ConductCancelRequest;
import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.dto.ConductGrantRequest;
import com.remake.gone.conduct.dto.ConductRecordResponse;
import com.remake.gone.conduct.dto.ConductStudentRecordResponse;
import com.remake.gone.conduct.dto.ConductSummaryResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.enums.ConductStatus;
import com.remake.gone.conduct.enums.ConductType;
import com.remake.gone.conduct.exception.ConductErrorCode;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import com.remake.gone.conduct.repository.ConductRecordRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상/벌점(Conduct) 도메인 서비스. */
@Service
@RequiredArgsConstructor
public class ConductService {

  private static final String STUDENT_ROLE_CODE = "STUDENT";
  private static final String ADMIN_ROLE_CODE = "ADMIN";

  private final ConductCategoryRepository conductCategoryRepository;
  private final ConductRecordRepository conductRecordRepository;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final ConductProperties conductProperties;

  /**
   * 활성 카테고리 목록을 조회합니다.
   *
   * @return {@code active = true}인 카테고리를 {@code id} 오름차순으로 정렬한 목록
   */
  @Transactional(readOnly = true)
  public List<ConductCategoryResponse> getCategories() {
    return conductCategoryRepository.findByActiveTrueOrderByIdAsc().stream()
        .map(ConductCategoryResponse::from)
        .toList();
  }

  /**
   * 교사가 학생에게 상/벌점을 부여합니다.
   *
   * @param teacherUserId 부여하는 교사 사용자 ID (Access Token에서 추출됨)
   * @param request       부여 요청 정보
   * @return 생성된 상/벌점 기록
   */
  @Transactional
  public ConductRecordResponse grantConduct(Long teacherUserId, ConductGrantRequest request) {
    ConductCategory category = conductCategoryRepository.findById(request.categoryId())
        .filter(ConductCategory::isActive)
        .orElseThrow(() -> new CustomException(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE));

    User student = userRepository.findById(request.studentUserId())
        .orElseThrow(() -> new CustomException(ConductErrorCode.STUDENT_NOT_FOUND));

    List<String> roles = userRoleRepository.findRoleCodesByUserId(request.studentUserId());
    if (!roles.contains(STUDENT_ROLE_CODE)) {
      throw new CustomException(ConductErrorCode.NOT_STUDENT_ROLE);
    }

    User teacher = userRepository.findById(teacherUserId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.NOT_FOUND));

    ConductRecord record = ConductRecord.builder()
        .student(student)
        .teacher(teacher)
        .category(category)
        .type(category.getType())
        .points(category.getPoints())
        .detail(request.detail())
        .build();

    return ConductRecordResponse.from(conductRecordRepository.save(record));
  }

  /**
   * 상/벌점 기록을 정정합니다.
   *
   * <p>TEACHER는 본인이 부여한 기록만 정정할 수 있습니다. ADMIN은 소유권 무관하게 정정할 수 있습니다.
   * CANCELED 상태인 기록은 정정할 수 없습니다.
   *
   * @param callerUserId  호출자 사용자 ID (Access Token에서 추출됨)
   * @param recordId      정정할 기록 ID
   * @param request       정정 요청 정보
   * @return 정정된 상/벌점 기록
   */
  @Transactional
  public ConductRecordResponse amendConduct(
      Long callerUserId, Long recordId, ConductAmendRequest request) {
    if (request.categoryId() == null && request.detail() == null) {
      throw new CustomException(CommonErrorCode.INVALID_REQUEST);
    }

    ConductRecord record = conductRecordRepository.findById(recordId)
        .orElseThrow(() -> new CustomException(ConductErrorCode.RECORD_NOT_FOUND));

    boolean isAdmin = userRoleRepository.findRoleCodesByUserId(callerUserId)
        .contains(ADMIN_ROLE_CODE);
    if (!isAdmin && !callerUserId.equals(record.getTeacher().getId())) {
      throw new CustomException(ConductErrorCode.NOT_RECORD_OWNER);
    }

    if (record.getStatus() == ConductStatus.CANCELED) {
      throw new CustomException(ConductErrorCode.ALREADY_CANCELED);
    }

    if (request.categoryId() != null) {
      ConductCategory newCategory = conductCategoryRepository.findById(request.categoryId())
          .filter(ConductCategory::isActive)
          .orElseThrow(() -> new CustomException(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE));
      record.setCategory(newCategory);
      record.setType(newCategory.getType());
      record.setPoints(newCategory.getPoints());
    }

    if (request.detail() != null) {
      record.setDetail(request.detail());
    }

    return ConductRecordResponse.from(record);
  }

  /**
   * 상/벌점 기록을 취소합니다.
   *
   * <p>TEACHER는 본인이 부여한 기록만 취소할 수 있습니다. ADMIN은 소유권 무관하게 취소할 수 있습니다.
   * 이미 취소된 기록은 다시 취소할 수 없습니다.
   *
   * @param callerUserId  호출자 사용자 ID (Access Token에서 추출됨)
   * @param recordId      취소할 기록 ID
   * @param request       취소 요청 정보
   * @return 취소된 상/벌점 기록
   */
  @Transactional
  public ConductRecordResponse cancelConduct(
      Long callerUserId, Long recordId, ConductCancelRequest request) {
    ConductRecord record = conductRecordRepository.findById(recordId)
        .orElseThrow(() -> new CustomException(ConductErrorCode.RECORD_NOT_FOUND));

    boolean isAdmin = userRoleRepository.findRoleCodesByUserId(callerUserId)
        .contains(ADMIN_ROLE_CODE);
    if (!isAdmin && !callerUserId.equals(record.getTeacher().getId())) {
      throw new CustomException(ConductErrorCode.NOT_RECORD_OWNER);
    }

    if (record.getStatus() == ConductStatus.CANCELED) {
      throw new CustomException(ConductErrorCode.ALREADY_CANCELED);
    }

    User cancelingUser = userRepository.findById(callerUserId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.NOT_FOUND));

    record.setStatus(ConductStatus.CANCELED);
    record.setCanceledAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    record.setCanceledBy(cancelingUser);
    record.setCancelReason(request.cancelReason());

    return ConductRecordResponse.from(record);
  }

  /**
   * 학생 본인의 누적 상/벌점 요약을 반환합니다.
   *
   * <p>전체 기간 기준으로 {@code ACTIVE} 상태인 기록만 집계합니다.
   *
   * @param studentUserId 조회 대상 학생 사용자 ID (Access Token에서 추출됨)
   * @return 총 상점·벌점·순 점수·임계치 초과 여부
   */
  @Transactional(readOnly = true)
  public ConductSummaryResponse getStudentSummary(Long studentUserId) {
    int totalMeritPoints = conductRecordRepository.sumPointsByStudentAndType(
        studentUserId, ConductType.MERIT, ConductStatus.ACTIVE);
    int totalDemeritPoints = conductRecordRepository.sumPointsByStudentAndType(
        studentUserId, ConductType.DEMERIT, ConductStatus.ACTIVE);
    int netScore = totalMeritPoints + totalDemeritPoints;
    int threshold = conductProperties.demeritThreshold();
    boolean overThreshold = Math.abs(totalDemeritPoints) >= threshold;
    return new ConductSummaryResponse(
        totalMeritPoints, totalDemeritPoints, netScore, threshold, overThreshold);
  }

  /**
   * 학생 본인의 상/벌점 이력을 필터·페이지네이션해 반환합니다.
   *
   * <p>취소된 기록({@code CANCELED})도 포함합니다.
   *
   * @param studentUserId 조회 대상 학생 사용자 ID (Access Token에서 추출됨)
   * @param type          종류 필터({@code null}이면 전체)
   * @param dateFrom      조회 시작일({@code null}이면 전체 기간)
   * @param dateTo        조회 종료일({@code null}이면 전체 기간)
   * @param page          페이지 번호(0부터 시작)
   * @param size          페이지 크기(1~100)
   * @return 페이지네이션된 이력 목록
   */
  @Transactional(readOnly = true)
  public PageResponse<ConductStudentRecordResponse> getStudentRecords(
      Long studentUserId,
      ConductType type,
      LocalDate dateFrom,
      LocalDate dateTo,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new CustomException(ConductErrorCode.INVALID_PAGE);
    }
    boolean hasFrom = dateFrom != null;
    boolean hasTo = dateTo != null;
    if (hasFrom != hasTo || (hasFrom && dateFrom.isAfter(dateTo))) {
      throw new CustomException(ConductErrorCode.INVALID_DATE_RANGE);
    }

    Page<ConductRecord> recordPage = conductRecordRepository.findByStudentWithFilters(
        studentUserId, type, dateFrom, dateTo, PageRequest.of(page, size));

    return new PageResponse<>(
        recordPage.getContent().stream()
            .map(ConductStudentRecordResponse::from)
            .toList(),
        recordPage.getNumber(),
        recordPage.getSize(),
        recordPage.getTotalElements(),
        recordPage.getTotalPages(),
        recordPage.hasNext()
    );
  }
}
