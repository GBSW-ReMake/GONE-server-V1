package com.remake.gone.outing.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.exception.OutingErrorCode;
import com.remake.gone.outing.repository.OutingRepository;
import com.remake.gone.outing.utils.OutingCodeGenerator;
import com.remake.gone.outing.utils.OutingTimeUtils;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
  private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;

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

    return toResponse(outing, student, teacher);
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

  private OutingResponse toResponse(Outing outing, User student, User teacher) {
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
        outing.getStatus());
  }

  private record TimeRange(LocalTime start, LocalTime end) {}
}
