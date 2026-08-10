package com.remake.gone.outing.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingQueryPeriod;
import com.remake.gone.outing.enums.OutingQueryStatus;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.exception.OutingErrorCode;
import com.remake.gone.outing.repository.OutingRepository;
import com.remake.gone.outing.utils.OutingCodeGenerator;
import com.remake.gone.outing.utils.OutingDateRange;
import com.remake.gone.outing.utils.OutingQueryPeriodResolver;
import com.remake.gone.outing.utils.OutingTimeUtils;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외출증 신청 비즈니스 로직을 처리하는 서비스.
 *
 * <p>"지금 이 순간"(오늘 날짜/현재 시각)은 서비스 내부에서 직접 구하지 않고 호출자(컨트롤러)로
 * 부터 파라미터로 받는다 — {@code TimetableController}/{@code MealController}가 날짜를 파라미터로
 * 받는 것과 같은 이유로, 단위 테스트에서 실제 시각에 의존하지 않고 임의의 날짜/시각을 주입해
 * 결정론적으로 검증할 수 있게 하기 위함이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutingService {

  private static final DateTimeFormatter YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter HM_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
  private static final String STUDENT_ROLE_CODE = "STUDENT";
  private static final String TEACHER_ROLE_CODE = "TEACHER";
  private static final String DISCIPLINE_ROLE_CODE = "DISCIPLINE";
  private static final String ADMIN_ROLE_CODE = "ADMIN";
  private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;
  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 100;

  private final OutingRepository outingRepository;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final R2FileService r2FileService;

  /**
   * 학생이 정해진 시간대(프리셋) 또는 직접 입력한 시간대(커스텀)에 외출증을 신청한다.
   *
   * @param studentUserId 신청하는 학생 사용자 ID (Access Token에서 추출됨)
   * @param request       신청 요청 정보
   * @param today         "오늘" 날짜(KST) — 날짜 범위/마감 검증 기준
   * @param now           "지금" 시각(KST) — 마감 검증 기준
   * @return 생성된 외출증 정보
   */
  @Transactional
  public OutingResponse applyOuting(
      Long studentUserId, OutingApplyRequest request, LocalDate today, LocalTime now) {
    validateStudentRole(studentUserId);

    LocalDate outingDate = parseOutingDate(request.outingDate());
    validateDateRange(outingDate, today);

    TimeRange timeRange = resolveTimeRange(request);
    validateDeadline(outingDate, timeRange.start(), today, now);

    User teacher = findValidTeacher(request.teacherUserId());

    // 그 학생의 User 행에 배타적 락을 걸어, 같은 학생의 동시 요청을 직렬화한다
    // (docs/outing-domain.md "동시성 처리" 참고).
    User student = userRepository.findByIdForUpdate(studentUserId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));
    validateClassAssigned(student.getGbsw());

    validateNoOverlap(studentUserId, outingDate, timeRange);

    Outing outing = saveWithGeneratedCode(
        student, teacher, request.reason(), outingDate, request.timeSlot(), timeRange);

    return toResponse(outing, student, teacher, today, now);
  }

  /**
   * 담당 선생님이 학생의 외출증 신청을 승인합니다.
   *
   * @param teacherUserId 승인을 요청한 선생님 사용자 ID (Access Token에서 추출됨)
   * @param code          승인할 외출증의 외부 식별자 코드
   * @param now           "지금" 시각(KST) — {@code approvedAt} 기록 + 마감 재계산에 사용(#42).
   *                      DB의 {@code status}가 아직 {@code PENDING}이어도, 이 시각 기준으로
   *                      마감이 지났으면 {@code DEADLINE_PASSED}로 거부한다.
   * @return 승인된 외출증 정보
   */
  @Transactional
  public OutingResponse approveOuting(Long teacherUserId, String code, LocalDateTime now) {
    Outing outing = outingRepository.findByCode(code)
        .orElseThrow(() -> new CustomException(OutingErrorCode.OUTING_NOT_FOUND));

    if (!outing.getTeacher().getId().equals(teacherUserId)) {
      throw new CustomException(OutingErrorCode.TEACHER_MISMATCH);
    }
    if (outing.getStatus() != OutingStatus.PENDING) {
      throw new CustomException(OutingErrorCode.ALREADY_PROCESSED);
    }
    validateNotPastDeadline(outing, now);

    outing.setStatus(OutingStatus.APPROVED);
    outing.setApprovedAt(now);
    outingRepository.save(outing);

    return toResponse(
        outing, outing.getStudent(), outing.getTeacher(), now.toLocalDate(), now.toLocalTime());
  }

  /**
   * 담당 선생님이 학생의 외출증 신청을 거절합니다.
   *
   * @param teacherUserId  거절을 요청한 선생님 사용자 ID (Access Token에서 추출됨)
   * @param code           거절할 외출증의 외부 식별자 코드
   * @param rejectedReason 거절 사유
   * @param now            "지금" 시각(KST) — 응답 변환 시 유효 상태 계산 + 마감 재계산에
   *                       사용(#42). DB의 {@code status}가 아직 {@code PENDING}이어도, 이
   *                       시각 기준으로 마감이 지났으면 {@code DEADLINE_PASSED}로 거부한다.
   * @return 거절된 외출증 정보
   */
  @Transactional
  public OutingResponse rejectOuting(
      Long teacherUserId, String code, String rejectedReason, LocalDateTime now) {
    Outing outing = outingRepository.findByCode(code)
        .orElseThrow(() -> new CustomException(OutingErrorCode.OUTING_NOT_FOUND));

    if (!outing.getTeacher().getId().equals(teacherUserId)) {
      throw new CustomException(OutingErrorCode.TEACHER_MISMATCH);
    }
    if (outing.getStatus() != OutingStatus.PENDING) {
      throw new CustomException(OutingErrorCode.ALREADY_PROCESSED);
    }
    validateNotPastDeadline(outing, now);

    outing.setStatus(OutingStatus.REJECTED);
    outing.setRejectedReason(rejectedReason);
    outingRepository.save(outing);

    return toResponse(
        outing, outing.getStudent(), outing.getTeacher(), now.toLocalDate(), now.toLocalTime());
  }

  /**
   * 학생 본인이 신청한 외출증을 조회합니다(#41).
   *
   * @param studentUserId 조회하는 학생 사용자 ID (Access Token에서 추출됨)
   * @param period        조회 기간 프리셋
   * @param dateFrom      {@code period == CUSTOM}일 때의 시작일
   * @param dateTo        {@code period == CUSTOM}일 때의 종료일
   * @param statusFilter  걸러볼 상태(유효 상태 기준). {@code null}이면 전부 반환
   * @param page          페이지 번호(0부터 시작)
   * @param size          페이지 크기(1~100)
   * @param today         "오늘" 날짜(KST) — 기간 기본값/유효 상태 계산에 사용
   * @param now           "지금" 시각(KST) — 유효 상태 계산에 사용
   * @return 조건에 맞는 외출증의 페이지네이션된 목록(날짜/시작 시각 오름차순)
   */
  @Transactional(readOnly = true)
  public PageResponse<OutingResponse> getMyRequests(
      Long studentUserId, OutingQueryPeriod period, LocalDate dateFrom, LocalDate dateTo,
      OutingQueryStatus statusFilter, int page, int size, LocalDate today, LocalTime now) {
    validatePageParams(page, size);
    OutingDateRange range = resolveQueryRange(period, dateFrom, dateTo, today);
    List<Outing> outings = outingRepository
        .findByStudentIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(
            studentUserId, range.from(), range.to());
    List<OutingResponse> filtered = toFilteredResponses(outings, statusFilter, today, now);
    return PageResponse.of(filtered, page, size);
  }

  /**
   * 담당 선생님으로 지정된 외출증을 조회합니다(#41).
   *
   * @param teacherUserId 조회하는 선생님 사용자 ID (Access Token에서 추출됨)
   * @param period        조회 기간 프리셋
   * @param dateFrom      {@code period == CUSTOM}일 때의 시작일
   * @param dateTo        {@code period == CUSTOM}일 때의 종료일
   * @param statusFilter  걸러볼 상태(유효 상태 기준). {@code null}이면 전부 반환
   * @param page          페이지 번호(0부터 시작)
   * @param size          페이지 크기(1~100)
   * @param today         "오늘" 날짜(KST) — 기간 기본값/유효 상태 계산에 사용
   * @param now           "지금" 시각(KST) — 유효 상태 계산에 사용
   * @return 조건에 맞는 외출증의 페이지네이션된 목록(날짜/시작 시각 오름차순)
   */
  @Transactional(readOnly = true)
  public PageResponse<OutingResponse> getReceivedOutings(
      Long teacherUserId, OutingQueryPeriod period, LocalDate dateFrom, LocalDate dateTo,
      OutingQueryStatus statusFilter, int page, int size, LocalDate today, LocalTime now) {
    validatePageParams(page, size);
    OutingDateRange range = resolveQueryRange(period, dateFrom, dateTo, today);
    List<Outing> outings = outingRepository
        .findByTeacherIdAndOutingDateBetweenOrderByOutingDateAscStartTimeAsc(
            teacherUserId, range.from(), range.to());
    List<OutingResponse> filtered = toFilteredResponses(outings, statusFilter, today, now);
    return PageResponse.of(filtered, page, size);
  }

  private void validatePageParams(int page, int size) {
    if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
      throw new CustomException(OutingErrorCode.INVALID_PAGE_PARAMS);
    }
  }

  /**
   * 승인/거절 요청 시점을 기준으로 그 외출증의 마감이 이미 지났는지 재계산합니다(#42). DB의
   * {@code status}가 아직 {@code PENDING}이어도(스케줄러가 아직 반영 전이어도) 이 시각 기준
   * 마감이 지났으면 거부한다 — DB 값에 의존하지 않는다.
   *
   * @param outing 검사할 외출증
   * @param now    "지금" 시각(KST)
   */
  private void validateNotPastDeadline(Outing outing, LocalDateTime now) {
    if (OutingTimeUtils.isPastDeadline(
        outing.getOutingDate(), outing.getStartTime(), now.toLocalDate(), now.toLocalTime())) {
      throw new CustomException(OutingErrorCode.DEADLINE_PASSED);
    }
  }

  /**
   * 외출증 단건을 상세 조회합니다(#41). 신청 학생 본인, 지정된 담당 선생님, 또는
   * {@code DISCIPLINE}/{@code ADMIN} 역할 보유자만 조회할 수 있습니다.
   *
   * @param callerUserId 조회를 요청한 사용자 ID (Access Token에서 추출됨)
   * @param code         조회할 외출증의 외부 식별자 코드
   * @param today        "오늘" 날짜(KST) — 유효 상태 계산에 사용
   * @param now          "지금" 시각(KST) — 유효 상태 계산에 사용
   * @return 외출증 상세 정보
   */
  @Transactional(readOnly = true)
  public OutingResponse getOutingDetail(
      Long callerUserId, String code, LocalDate today, LocalTime now) {
    Outing outing = outingRepository.findByCode(code)
        .orElseThrow(() -> new CustomException(OutingErrorCode.OUTING_NOT_FOUND));
    validateDetailAccess(callerUserId, outing);
    return toResponse(outing, outing.getStudent(), outing.getTeacher(), today, now);
  }

  /**
   * 마감이 지난 {@code PENDING} 외출증의 ID를 전부 조회합니다(#42). {@code
   * OutingMissedScheduler}가 1분 주기로 이 메서드로 대상을 찾은 뒤, 각 ID를 {@link
   * #markSingleOutingAsMissed(Long)}로 건별 갱신한다.
   *
   * <p>한 트랜잭션에서 전체를 읽고 한 번에 갱신하지 않는 이유: 이 조회와 승인/거절 요청의
   * 커밋이 겹치면(스케줄러가 읽은 뒤 승인/거절이 먼저 커밋되고, 스케줄러가 그 스냅샷을
   * 그대로 다시 덮어쓰는 경우) {@code approvedAt}/{@code rejectedReason}까지 조용히
   * 유실될 수 있다(#42 코드 리뷰에서 확인). 조회와 갱신을 분리하고 건별로 독립 트랜잭션에서
   * 갱신하면, 그 사이 승인/거절이 먼저 커밋된 건은 {@link #markSingleOutingAsMissed(Long)}가
   * 그 시점에 다시 확인해 걸러내거나 {@link Outing#getVersion() 버전} 충돌로 감지한다.
   *
   * @param today "오늘" 날짜(KST)
   * @param now   "지금" 시각(KST)
   * @return 마감이 지난 {@code PENDING} 외출증의 ID 목록
   */
  @Transactional(readOnly = true)
  public List<Long> findOverdueOutingIds(LocalDate today, LocalTime now) {
    return outingRepository.findByStatus(OutingStatus.PENDING).stream()
        .filter(outing -> OutingTimeUtils.isPastDeadline(
            outing.getOutingDate(), outing.getStartTime(), today, now))
        .map(Outing::getId)
        .toList();
  }

  /**
   * 외출증 하나를 {@code MISSED}로 갱신합니다(#42). 독립된 트랜잭션에서 실행되도록
   * {@code OutingMissedScheduler}가 {@link #findOverdueOutingIds(LocalDate, LocalTime)}로
   * 찾은 ID마다 이 메서드를 개별 호출한다 — 배치 하나로 묶으면 한 건의 낙관적 락 충돌이
   * 나머지 건까지 전부 롤백시키기 때문이다.
   *
   * <p>조회 시점에 이미 {@code PENDING}이 아니면(승인/거절이 먼저 커밋됨) 조용히 건너뛴다.
   * 저장 시점에 {@link ObjectOptimisticLockingFailureException}이 나면(그 사이 다른
   * 트랜잭션이 먼저 갱신함) 경고 로그만 남기고 건너뛴다 — 두 경우 모두 승인/거절 결과를
   * 덮어쓰지 않는 것이 이 메서드가 실패하는 것보다 낫다.
   *
   * @param outingId 갱신할 외출증의 내부 PK
   */
  @Transactional
  public void markSingleOutingAsMissed(Long outingId) {
    Optional<Outing> found = outingRepository.findById(outingId);
    if (found.isEmpty() || found.get().getStatus() != OutingStatus.PENDING) {
      return;
    }
    Outing outing = found.get();
    outing.setStatus(OutingStatus.MISSED);
    try {
      outingRepository.save(outing);
    } catch (ObjectOptimisticLockingFailureException e) {
      log.warn("외출증 MISSED 갱신 중 낙관적 락 충돌로 건너뜀(outingId={})", outingId, e);
    }
  }

  private void validateStudentRole(Long studentUserId) {
    List<String> roles = userRoleRepository.findRoleCodesByUserId(studentUserId);
    if (!roles.contains(STUDENT_ROLE_CODE)) {
      throw new CustomException(OutingErrorCode.STUDENT_ROLE_REQUIRED);
    }
  }

  private LocalDate parseOutingDate(String outingDate) {
    try {
      return LocalDate.parse(outingDate, YMD_FORMATTER);
    } catch (DateTimeParseException e) {
      throw new CustomException(OutingErrorCode.INVALID_DATE_OR_TIME);
    }
  }

  private void validateDateRange(LocalDate outingDate, LocalDate today) {
    LocalDate thisFriday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    if (outingDate.isBefore(today) || outingDate.isAfter(thisFriday)) {
      throw new CustomException(OutingErrorCode.INVALID_DATE_OR_TIME);
    }
  }

  private TimeRange resolveTimeRange(OutingApplyRequest request) {
    OutingTimeSlot timeSlot = request.timeSlot();
    if (timeSlot.isPreset()) {
      return new TimeRange(timeSlot.getStartTime(), timeSlot.getEndTime());
    }

    LocalTime start = parseCustomTime(request.customStartTime());
    LocalTime end = parseCustomTime(request.customEndTime());
    if (start.isBefore(OutingTimeSlot.CUSTOM_WINDOW_START)
        || end.isAfter(OutingTimeSlot.CUSTOM_WINDOW_END)
        || !end.isAfter(start)) {
      throw new CustomException(OutingErrorCode.INVALID_CUSTOM_TIME_RANGE);
    }
    return new TimeRange(start, end);
  }

  private LocalTime parseCustomTime(String value) {
    if (value == null) {
      throw new CustomException(OutingErrorCode.INVALID_CUSTOM_TIME_RANGE);
    }
    try {
      return LocalTime.parse(value, HM_FORMATTER);
    } catch (DateTimeParseException e) {
      throw new CustomException(OutingErrorCode.INVALID_CUSTOM_TIME_RANGE);
    }
  }

  private void validateDeadline(
      LocalDate outingDate, LocalTime startTime, LocalDate today, LocalTime now) {
    if (outingDate.isEqual(today) && !now.isBefore(startTime)) {
      throw new CustomException(OutingErrorCode.INVALID_DATE_OR_TIME);
    }
  }

  private void validateClassAssigned(Gbsw studentGbsw) {
    if (studentGbsw.getGrade() == null || studentGbsw.getClassNo() == null) {
      throw new CustomException(GbswErrorCode.NO_CLASS_ASSIGNED);
    }
  }

  private User findValidTeacher(Long teacherUserId) {
    User teacher = userRepository.findById(teacherUserId)
        .orElseThrow(() -> new CustomException(OutingErrorCode.TEACHER_NOT_FOUND));
    List<String> roles = userRoleRepository.findRoleCodesByUserId(teacherUserId);
    if (!roles.contains(TEACHER_ROLE_CODE)) {
      throw new CustomException(OutingErrorCode.TEACHER_NOT_FOUND);
    }
    return teacher;
  }

  private void validateNoOverlap(Long studentUserId, LocalDate outingDate, TimeRange timeRange) {
    List<Outing> activeOutings = outingRepository.findByStudentIdAndOutingDateAndStatusIn(
        studentUserId, outingDate, OutingStatus.ACTIVE_STATUSES);
    boolean overlapExists = activeOutings.stream().anyMatch(existing -> OutingTimeUtils.overlaps(
        existing.getStartTime(), existing.getEndTime(), timeRange.start(), timeRange.end()));
    if (overlapExists) {
      throw new CustomException(OutingErrorCode.TIME_OVERLAP);
    }
  }

  private Outing saveWithGeneratedCode(
      User student, User teacher, String reason, LocalDate outingDate,
      OutingTimeSlot timeSlot, TimeRange timeRange) {

    DataIntegrityViolationException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
      Outing outing = Outing.builder()
          .code(OutingCodeGenerator.generate())
          .student(student)
          .teacher(teacher)
          .reason(reason)
          .outingDate(outingDate)
          .timeSlot(timeSlot)
          .startTime(timeRange.start())
          .endTime(timeRange.end())
          .status(OutingStatus.PENDING)
          .build();
      try {
        return outingRepository.save(outing);
      } catch (DataIntegrityViolationException e) {
        lastFailure = e;
      }
    }
    // code 유니크 제약 위반이 아닌 다른 원인일 수도 있으므로, 원본 예외를 삼키지 않고 그대로
    // 던진다 — GlobalExceptionHandler의 DataIntegrityViolationException 핸들러가 409로 변환한다.
    log.warn("외출증 code 생성 {}회 재시도 모두 실패(studentId={})",
        MAX_CODE_GENERATION_ATTEMPTS, student.getId(), lastFailure);
    throw lastFailure;
  }

  // OutingResponse(record)에 정적 팩토리로 옮기지 않고 Service에 둔다: 이 프로젝트의 모든
  // 도메인(UserService/MealService/TimetableService 등)이 "Response는 순수 데이터, 매핑은
  // Service 책임"을 따르고, r2FileService.generateDownloadUrl(...) 호출(Spring 빈 의존)까지
  // 포함돼 있어 record로 옮기면 DTO가 프레임워크에 의존하게 된다.
  //
  // today/now는 유효 상태(#41 MISSED 판정) 계산에만 쓰인다. applyOuting/approveOuting/
  // rejectOuting 호출부는 방금 생성/승인/거절된 건을 넘기므로 status가 PENDING일 수 없어
  // 실질적으로 값이 바뀌지 않는다 — 그래도 매 호출부가 "지금 이 순간"을 파라미터로 직접
  // 넘기는 이 클래스의 기존 규칙(클래스 상단 Javadoc 참고)을 그대로 따른다.
  private OutingResponse toResponse(
      Outing outing, User student, User teacher, LocalDate today, LocalTime now) {
    String studentProfileImageUrl = student.getProfileImageKey() != null
        ? r2FileService.generateDownloadUrl(student.getProfileImageKey())
        : null;
    Gbsw studentGbsw = student.getGbsw();
    return new OutingResponse(
        outing.getCode(),
        student.getName(),
        studentProfileImageUrl,
        studentGbsw.getName(),
        studentGbsw.getGrade(),
        studentGbsw.getClassNo(),
        teacher.getGbsw().getName(),
        outing.getReason(),
        outing.getOutingDate().format(YMD_FORMATTER),
        outing.getTimeSlot(),
        outing.getStartTime().format(HM_FORMATTER),
        outing.getEndTime().format(HM_FORMATTER),
        resolveEffectiveStatus(outing, today, now),
        outing.getRejectedReason());
  }

  /**
   * 저장된 상태가 {@code PENDING}이고 마감(시작 시각)이 지났으면 {@code MISSED}로, 아니면
   * 저장된 값 그대로 반환한다(#41). DB는 바꾸지 않는다 — 응답 변환 시점의 실시간 계산이다.
   */
  private OutingStatus resolveEffectiveStatus(Outing outing, LocalDate today, LocalTime now) {
    if (outing.getStatus() == OutingStatus.PENDING
        && OutingTimeUtils.isPastDeadline(
            outing.getOutingDate(), outing.getStartTime(), today, now)) {
      return OutingStatus.MISSED;
    }
    return outing.getStatus();
  }

  /**
   * {@code period}/{@code dateFrom}/{@code dateTo} 조합을 검증하고 실제 조회 범위를 계산한다
   * (#41). 검증 자체는 이 서비스가 하고, 계산은 순수 함수인
   * {@link OutingQueryPeriodResolver}에 위임한다.
   */
  private OutingDateRange resolveQueryRange(
      OutingQueryPeriod period, LocalDate dateFrom, LocalDate dateTo, LocalDate today) {
    validatePeriodParams(period, dateFrom, dateTo);
    OutingDateRange range = OutingQueryPeriodResolver.resolve(period, today, dateFrom, dateTo);
    if (range.from().isAfter(range.to())) {
      throw new CustomException(OutingErrorCode.INVALID_DATE_RANGE);
    }
    return range;
  }

  private void validatePeriodParams(
      OutingQueryPeriod period, LocalDate dateFrom, LocalDate dateTo) {
    boolean hasCustomDates = dateFrom != null || dateTo != null;
    if (period == OutingQueryPeriod.CUSTOM) {
      if (dateFrom == null || dateTo == null) {
        throw new CustomException(OutingErrorCode.INVALID_PERIOD_PARAMS);
      }
    } else if (hasCustomDates) {
      throw new CustomException(OutingErrorCode.INVALID_PERIOD_PARAMS);
    }
  }

  private List<OutingResponse> toFilteredResponses(
      List<Outing> outings, OutingQueryStatus statusFilter, LocalDate today, LocalTime now) {
    OutingStatus effectiveFilter = statusFilter == null ? null : statusFilter.toOutingStatus();
    return outings.stream()
        .map(outing -> toResponse(outing, outing.getStudent(), outing.getTeacher(), today, now))
        .filter(response -> effectiveFilter == null || response.status() == effectiveFilter)
        .toList();
  }

  private void validateDetailAccess(Long callerUserId, Outing outing) {
    boolean isRelated = outing.getStudent().getId().equals(callerUserId)
        || outing.getTeacher().getId().equals(callerUserId);
    if (isRelated) {
      return;
    }
    List<String> roles = userRoleRepository.findRoleCodesByUserId(callerUserId);
    if (roles.contains(DISCIPLINE_ROLE_CODE) || roles.contains(ADMIN_ROLE_CODE)) {
      return;
    }
    throw new CustomException(OutingErrorCode.ACCESS_DENIED);
  }

  // applyOuting() 한 메서드 내부(시간 확정 → 겹침 체크) 계산에서만 쓰이고 API/도메인 경계를
  // 넘지 않아 private record로 남긴다 — 다른 곳에서도 필요해지면(예: 출발/도착) 그때 공용
  // 타입으로 승격한다.
  private record TimeRange(LocalTime start, LocalTime end) {}
}
