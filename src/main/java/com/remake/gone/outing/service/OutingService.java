package com.remake.gone.outing.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.schedule.service.ScheduledTaskService;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.service.NotificationService;
import com.remake.gone.outing.config.OutingProperties;
import com.remake.gone.outing.dto.OutingActiveResponse;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingLocationPointResponse;
import com.remake.gone.outing.dto.OutingLocationRequest;
import com.remake.gone.outing.dto.OutingLocationsResponse;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.entity.OutingLocation;
import com.remake.gone.outing.enums.OutingQueryPeriod;
import com.remake.gone.outing.enums.OutingQueryStatus;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.exception.OutingErrorCode;
import com.remake.gone.outing.repository.OutingLocationRepository;
import com.remake.gone.outing.repository.OutingRepository;
import com.remake.gone.outing.utils.GeoUtils;
import com.remake.gone.outing.utils.OutingCodeGenerator;
import com.remake.gone.outing.utils.OutingDateRange;
import com.remake.gone.outing.utils.OutingQueryPeriodResolver;
import com.remake.gone.outing.utils.OutingTimeUtils;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

  // 복귀 리마인더(#99) — #120 ScheduledTaskService에 등록하는 task_type 문자열.
  private static final String OUTING_TIMEOUT_TASK_TYPE = "OUTING_TIMEOUT";
  // 시간 초과 알림 재발송 간격/발송 상한(가정값, 운영해보고 조정 가능 — #99 기획서
  // "아직 결정 안 된 것" 참고).
  private static final Duration TIMEOUT_REMINDER_INTERVAL = Duration.ofMinutes(5);
  private static final Duration TIMEOUT_REMINDER_CAP = Duration.ofHours(3);
  // 위치 기반 복귀 리마인더(#99) 스로틀 간격 — 재발송 간격과 값은 같지만 서로 다른 감지
  // 경로(시간 초과 vs 위치 핑)라 별도 상수로 분리해뒀다.
  private static final Duration LOCATION_REMINDER_INTERVAL = Duration.ofMinutes(5);

  // getMyRequests/getReceivedOutings 조회 정렬 기준(#41 도입, #96에서 DB 페이지네이션으로
  // 전환하며 id를 보조 정렬 키로 추가) — outingDate/startTime이 같은 두 건이 존재할 수 있어
  // (예: 거절된 뒤 같은 시간대로 재신청) 페이지 경계에서 순서가 흔들리지 않도록 한다.
  private static final Sort LIST_QUERY_SORT = Sort.by(
      Sort.Order.asc("outingDate"), Sort.Order.asc("startTime"), Sort.Order.asc("id"));

  // getActiveOutings(#96) 정렬 기준 — 가장 오래 나가 있는 학생이 먼저 보이도록 departedAt
  // 오름차순, departedAt이 초 단위 정밀도라 같은 초에 여러 학생이 출발할 수 있어 id를 보조
  // 정렬 키로 추가한다(동률 시 페이지 경계에서 학생이 누락되는 것을 방지).
  private static final Sort ACTIVE_LIST_SORT =
      Sort.by(Sort.Order.asc("departedAt"), Sort.Order.asc("id"));

  // getDailyOverview(#98) 정렬 기준 — 이미 특정 날짜 하루로 좁혀진 조회라 outingDate 정렬은
  // 의미가 없고, 그 하루 안에서 이른 시간대가 먼저 보이도록 startTime 오름차순 + id 보조
  // 정렬(동률 시 페이지 경계 안정성)을 쓴다.
  private static final Sort DAILY_OVERVIEW_SORT =
      Sort.by(Sort.Order.asc("startTime"), Sort.Order.asc("id"));

  private final OutingRepository outingRepository;
  private final OutingLocationRepository outingLocationRepository;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final R2FileService r2FileService;
  private final OutingProperties outingProperties;
  private final NotificationService notificationService;
  private final ScheduledTaskService scheduledTaskService;

  // 위치 기반 복귀 리마인더(#99) 마지막 발송 시각 스로틀. outingId별로 관리하며, 서버
  // 재시작으로 초기화돼도 문제없다 — 최악의 경우 재시작 직후 핑 한 번에 대해 스로틀이
  // 리셋되어 알림이 한 번 더 갈 뿐이다(#99 기획서 참고).
  private final ConcurrentMap<Long, LocalDateTime> lastLocationReminderAt =
      new ConcurrentHashMap<>();

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
    StatusFilterParams filter = resolveStatusFilterParams(statusFilter);
    Pageable pageable = PageRequest.of(page, size, LIST_QUERY_SORT);
    Page<Outing> outings = outingRepository.findStudentRequestsPage(
        studentUserId, range.from(), range.to(),
        filter.statusEq(), filter.wantExpired(), today, now, pageable);
    Page<OutingResponse> responses = outings.map(
        outing -> toResponse(outing, outing.getStudent(), outing.getTeacher(), today, now));
    return PageResponse.of(responses);
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
    StatusFilterParams filter = resolveStatusFilterParams(statusFilter);
    Pageable pageable = PageRequest.of(page, size, LIST_QUERY_SORT);
    Page<Outing> outings = outingRepository.findTeacherReceivedPage(
        teacherUserId, range.from(), range.to(),
        filter.statusEq(), filter.wantExpired(), today, now, pageable);
    Page<OutingResponse> responses = outings.map(
        outing -> toResponse(outing, outing.getStudent(), outing.getTeacher(), today, now));
    return PageResponse.of(responses);
  }

  /**
   * {@code statusFilter}(응답에 노출되는 유효 상태 기준)를 {@code status} 컬럼과 직접 비교
   * 가능한 값({@code statusEq}) + 마감 여부 플래그({@code wantExpired})로 변환한다(#96,
   * #98에서 MISSED 케이스 수정). 이 변환이 필요한 이유는
   * {@link OutingRepository#findStudentRequestsPage}의 Javadoc 참고 — {@code PENDING}이
   * 마감을 넘겨도 {@code OutingMissedScheduler}(#42)가 반영하기 전까지는 DB 값이 그대로
   * {@code PENDING}이라, 단일 컬럼 비교로는 "마감 전 PENDING만"과 "유효 상태
   * MISSED"(DB가 이미 MISSED이거나 그 반영 빈틈 구간의 PENDING)를 구분할 수 없다.
   *
   * <p>{@code MISSED}는 {@code statusEq}를 {@code null}로 둔다 — DB가 이미 MISSED인
   * 행까지 같이 잡아야 해서, "DB status가 PENDING"이라는 제약을 미리 걸면 안 되기 때문이다
   * (그 판단은 리포지토리 쿼리 안에서 {@code status = MISSED OR (status = PENDING AND
   * 마감 지남)}으로 처리한다). 착수 전 {@code statusEq = PENDING}으로 구현했다가, 이미
   * 스케줄러가 MISSED로 반영한 행이 필터에서 누락되는 걸 #98 QA에서 실제 서버로 확인하고
   * 고쳤다.
   */
  private StatusFilterParams resolveStatusFilterParams(OutingQueryStatus statusFilter) {
    if (statusFilter == null) {
      return new StatusFilterParams(null, null);
    }
    if (statusFilter == OutingQueryStatus.PENDING) {
      return new StatusFilterParams(OutingStatus.PENDING, false);
    }
    if (statusFilter == OutingQueryStatus.MISSED) {
      return new StatusFilterParams(null, true);
    }
    return new StatusFilterParams(statusFilter.toOutingStatus(), null);
  }

  private record StatusFilterParams(OutingStatus statusEq, Boolean wantExpired) {}

  /**
   * 지금 외출 중(DEPARTED)인 학생 목록을 조회합니다(#96). 선도부/선생님이 별도 위치 정보 없이
   * "누가 지금 밖에 있는지"만 빠르게 훑어보는 용도라 좌표는 응답에 포함하지 않는다(위치는
   * #97에서 더 좁은 권한으로 제공).
   *
   * <p>도착 보고 없이 방치된 외출증(자정을 넘겨도 자동 정리되지 않는 기존 리스크)을 날짜
   * 필터로 가리지 않는다 — 오래 방치된 건일수록 선도부가 확인해야 할 이상 신호이므로, 숨기는
   * 대신 {@code departedAt} 오름차순 정렬로 목록 맨 위에 노출한다(보스 확정, 2026-08-25).
   * 근본적인 자동 정리는 이 이슈 범위 밖이며 #102에서 다룬다.
   *
   * @param page 페이지 번호(0부터 시작)
   * @param size 페이지 크기(1~100)
   * @return 지금 외출 중인 학생 목록의 페이지네이션된 결과({@code departedAt} 오름차순 —
   *         가장 오래 나가 있는 학생이 먼저 보임)
   */
  @Transactional(readOnly = true)
  public PageResponse<OutingActiveResponse> getActiveOutings(int page, int size) {
    validatePageParams(page, size);
    Pageable pageable = PageRequest.of(page, size, ACTIVE_LIST_SORT);
    Page<Outing> outings = outingRepository.findByStatus(OutingStatus.DEPARTED, pageable);
    return PageResponse.of(outings.map(this::toActiveResponse));
  }

  /**
   * 특정 날짜(기본값 오늘)의 외출증 전체 현황을 조회합니다(#98). 학생/선생님으로 좁히지
   * 않고 그날 신청된 모든 외출증(대기/승인/거절/출발/도착/마감 포함)을 보여주는 관리용
   * 조회라, {@code #96}(지금 나가있는 사람만)과 반대로 하루치 전체 흐름을 파악하는 용도다.
   *
   * @param date         조회할 외출 날짜. {@code null}이면 {@code today}를 사용
   * @param statusFilter 걸러볼 상태(유효 상태 기준). {@code null}이면 전부 반환
   * @param page         페이지 번호(0부터 시작)
   * @param size         페이지 크기(1~100)
   * @param today        "오늘" 날짜(KST) — {@code date} 기본값 + 유효 상태 계산에 사용
   * @param now          "지금" 시각(KST) — 유효 상태 계산에 사용
   * @return 조건에 맞는 외출증의 페이지네이션된 목록({@code startTime} 오름차순)
   */
  @Transactional(readOnly = true)
  public PageResponse<OutingResponse> getDailyOverview(
      LocalDate date, OutingQueryStatus statusFilter, int page, int size,
      LocalDate today, LocalTime now) {
    validatePageParams(page, size);
    LocalDate targetDate = date != null ? date : today;
    StatusFilterParams filter = resolveStatusFilterParams(statusFilter);
    Pageable pageable = PageRequest.of(page, size, DAILY_OVERVIEW_SORT);
    Page<Outing> outings = outingRepository.findByOutingDatePage(
        targetDate, filter.statusEq(), filter.wantExpired(), today, now, pageable);
    Page<OutingResponse> responses = outings.map(
        outing -> toResponse(outing, outing.getStudent(), outing.getTeacher(), today, now));
    return PageResponse.of(responses);
  }

  private OutingActiveResponse toActiveResponse(Outing outing) {
    User student = outing.getStudent();
    String studentProfileImageUrl = student.getProfileImageKey() != null
        ? r2FileService.generateDownloadUrl(student.getProfileImageKey())
        : null;
    Gbsw studentGbsw = student.getGbsw();
    return new OutingActiveResponse(
        outing.getCode(),
        student.getName(),
        studentProfileImageUrl,
        studentGbsw.getName(),
        studentGbsw.getGrade(),
        studentGbsw.getClassNo(),
        outing.getReason(),
        outing.getTimeSlot(),
        outing.getDepartedAt(),
        outing.getEndTime().format(HM_FORMATTER));
  }

  /**
   * 학생 본인이 승인된 외출증의 출발을 보고합니다(#43).
   *
   * @param studentUserId 출발을 보고하는 학생 사용자 ID (Access Token에서 추출됨)
   * @param code          출발을 보고할 외출증의 외부 식별자 코드
   * @param request       출발 시점의 좌표
   * @param now           "지금" 시각(KST) — 운영시간 검증 + {@code departedAt} 기록에 사용
   * @return 출발 처리된 외출증 정보
   */
  @Transactional
  public OutingResponse departOuting(
      Long studentUserId, String code, OutingLocationRequest request, LocalDateTime now) {
    Outing outing = outingRepository.findByCode(code)
        .orElseThrow(() -> new CustomException(OutingErrorCode.OUTING_NOT_FOUND));
    validateOwnership(studentUserId, outing);
    validateOperatingHours(now.toLocalTime());
    if (outing.getStatus() != OutingStatus.APPROVED) {
      throw new CustomException(OutingErrorCode.ALREADY_PROCESSED);
    }
    validateSchoolRadius(request);

    outing.setStatus(OutingStatus.DEPARTED);
    outing.setDepartedAt(now);
    outing.setDepartedLatitude(request.latitude());
    outing.setDepartedLongitude(request.longitude());
    saveOrRejectAsAlreadyProcessed(outing);

    // 복귀 리마인더(#99) 등록 — 같은 트랜잭션 안에서 호출해 outing 상태 변경과
    // scheduled_task 등록이 원자적으로 함께 커밋/롤백된다(#120 ScheduledTaskService 참고).
    // scheduledAt은 outing의 종료 예정 시각(외출 날짜 + 종료 시각)이다.
    scheduledTaskService.schedule(
        OUTING_TIMEOUT_TASK_TYPE, outing.getId(),
        LocalDateTime.of(outing.getOutingDate(), outing.getEndTime()),
        TIMEOUT_REMINDER_INTERVAL, TIMEOUT_REMINDER_CAP);

    return toResponse(
        outing, outing.getStudent(), outing.getTeacher(), now.toLocalDate(), now.toLocalTime());
  }

  /**
   * 학생 본인이 출발한 외출증의 도착을 보고합니다(#43).
   *
   * @param studentUserId 도착을 보고하는 학생 사용자 ID (Access Token에서 추출됨)
   * @param code          도착을 보고할 외출증의 외부 식별자 코드
   * @param request       도착 시점의 좌표
   * @param now           "지금" 시각(KST) — 운영시간 검증 + {@code returnedAt} 기록에 사용
   * @return 도착 처리된 외출증 정보
   */
  @Transactional
  public OutingResponse returnOuting(
      Long studentUserId, String code, OutingLocationRequest request, LocalDateTime now) {
    Outing outing = outingRepository.findByCode(code)
        .orElseThrow(() -> new CustomException(OutingErrorCode.OUTING_NOT_FOUND));
    validateOwnership(studentUserId, outing);
    validateOperatingHours(now.toLocalTime());
    if (outing.getStatus() != OutingStatus.DEPARTED) {
      throw new CustomException(OutingErrorCode.ALREADY_PROCESSED);
    }
    validateSchoolRadius(request);

    outing.setStatus(OutingStatus.RETURNED);
    outing.setReturnedAt(now);
    outing.setReturnedLatitude(request.latitude());
    outing.setReturnedLongitude(request.longitude());
    saveOrRejectAsAlreadyProcessed(outing);

    // 복귀 리마인더(#99) 취소 — outing 상태 변경과 같은 트랜잭션에서 원자적으로 처리된다.
    scheduledTaskService.cancel(OUTING_TIMEOUT_TASK_TYPE, outing.getId());
    // 위치 기반 리마인더(#99) 스로틀 정리 — 더 이상 필요 없는 항목을 남겨두면 메모리가
    // 계속 늘어난다.
    lastLocationReminderAt.remove(outing.getId());

    return toResponse(
        outing, outing.getStudent(), outing.getTeacher(), now.toLocalDate(), now.toLocalTime());
  }

  /** {@link #checkAndNotifyTimeout}의 결과 — #120 {@code ScheduledTaskHandler}가 재실행
   * 여부를 판단하는 데 그대로 쓴다. */
  public enum TimeoutCheckResult {
    /** 이미 복귀했거나(RETURNED 등) outing이 사라졌음 — 더 이상 감시할 필요 없음. */
    RETURNED_OR_MISSING,
    /** 아직 DEPARTED 상태라 알림을 보냈고, 다음 간격에 다시 확인해야 함. */
    CONTINUE
  }

  /**
   * 외출 종료 시각이 지났는데 아직 복귀({@code RETURNED}) 처리되지 않은 학생에게 리마인더
   * 알림을 보냅니다(#99). {@code OutingTimeoutScheduledTaskHandler}(#120 {@code
   * ScheduledTaskHandler} 구현체)가 #120 폴링 루프에서 호출합니다 — 스케줄링 방식과 무관한
   * 순수 도메인 로직이라 별도 단위 테스트로 검증합니다.
   *
   * @param outingId 확인할 외출증의 내부 PK
   * @param now      "지금" 시각(KST)
   * @return 더 이상 재실행할 필요가 없으면 {@link TimeoutCheckResult#RETURNED_OR_MISSING},
   *     다음 간격에 다시 확인해야 하면 {@link TimeoutCheckResult#CONTINUE}
   */
  @Transactional
  public TimeoutCheckResult checkAndNotifyTimeout(Long outingId, LocalDateTime now) {
    Optional<Outing> found = outingRepository.findById(outingId);
    if (found.isEmpty() || found.get().getStatus() != OutingStatus.DEPARTED) {
      // 더 이상 감시할 필요가 없어진 시점 — 위치 기반 리마인더 스로틀 항목도 같이
      // 정리한다. returnOuting()이 아닌 경로(예: 이미 사라진 outing)로 감시가 끝나도
      // 이 맵에 항목이 영구히 남지 않게 한다(#99 코드 리뷰 지적).
      lastLocationReminderAt.remove(outingId);
      return TimeoutCheckResult.RETURNED_OR_MISSING;
    }
    Outing outing = found.get();
    User student = outing.getStudent();
    Long teacherId = outing.getTeacher().getId();
    notificationService.send(student.getId(),
        "외출 시간이 지났습니다",
        "예정된 복귀 시각이 지났습니다. 빨리 복귀해서 '도착' 버튼을 눌러주세요.",
        NotificationType.OUTING);
    notificationService.send(teacherId,
        "학생 미복귀 알림",
        student.getGbsw().getName() + " 학생이 아직 복귀하지 않았습니다 (외출증 " + outing.getCode() + ").",
        NotificationType.OUTING);
    // 담당 선생님이 DISCIPLINE 역할도 겸하면 이미 위에서 알림을 받았으므로 제외한다 —
    // 그대로 두면 같은 알림을 두 번 받는다(#99 코드 리뷰 지적).
    userRoleRepository.findUserIdsByRoleCode(DISCIPLINE_ROLE_CODE).stream()
        .filter(disciplineUserId -> !disciplineUserId.equals(teacherId))
        .forEach(disciplineUserId -> notificationService.send(disciplineUserId,
            "학생 미복귀 알림",
            student.getGbsw().getName() + " 학생이 아직 복귀하지 않았습니다 (외출증 " + outing.getCode() + ").",
            NotificationType.OUTING));
    return TimeoutCheckResult.CONTINUE;
  }

  /**
   * 학생 본인이 외출 중({@code DEPARTED}) 위치 핑을 전송합니다(#97). 핑이 학교 반경 안이면
   * "도착 확인" 리마인더 알림을 함께 보냅니다(#99, 위치 기반 복귀 감지 — 종료 시각과
   * 무관하게 학교 안에 있는데 도착 버튼을 안 누른 경우를 잡는다).
   *
   * @param studentUserId 핑을 전송하는 학생 사용자 ID (Access Token에서 추출됨)
   * @param code          핑을 전송할 외출증의 외부 식별자 코드
   * @param request       현재 위치 좌표
   * @param now           "지금" 시각(KST) — {@code recordedAt}으로 그대로 저장(클라이언트
   *                      시각을 신뢰하지 않는 이유는 {@link OutingLocation} 참고)
   */
  @Transactional
  public void recordLocationPing(
      Long studentUserId, String code, OutingLocationRequest request, LocalDateTime now) {
    Outing outing = outingRepository.findByCode(code)
        .orElseThrow(() -> new CustomException(OutingErrorCode.OUTING_NOT_FOUND));
    validateOwnership(studentUserId, outing);
    if (outing.getStatus() != OutingStatus.DEPARTED) {
      throw new CustomException(OutingErrorCode.NOT_DEPARTED_STATUS);
    }
    // depart/return과 달리 학교 반경 검증을 하지 않는다 — 핑은 "외출 중"을 계속 기록하는
    // 것이라 학교 밖에 있는 게 정상이다.
    outingLocationRepository.save(OutingLocation.builder()
        .outing(outing)
        .latitude(request.latitude())
        .longitude(request.longitude())
        .recordedAt(now)
        .build());

    // 위치 기반 복귀 리마인더(#99) — 핑이 올 때만 검사한다. 핑이 없으면 애초에 "아직 학교
    // 밖" 상태로 볼 수밖에 없어 알림을 못 보내도 논리적 공백이 생기지 않는다.
    double distance = GeoUtils.distanceMeters(
        request.latitude(), request.longitude(),
        outingProperties.schoolLatitude(), outingProperties.schoolLongitude());
    if (distance <= outingProperties.schoolRadiusMeters()) {
      // get-then-put이 아니라 compute로 판단+갱신을 한 번에 묶어야 한다 — 같은
      // outingId에 대한 동시 핑 두 건이 서로 상대의 put을 못 본 채 둘 다 스로틀을
      // 통과하는 경합을 없앤다(#99 코드 리뷰 지적). compute 람다는 부수 효과 없이
      // 순수해야 하므로, 알림 발송 여부만 계산하고 실제 발송은 람다 밖에서 한다.
      boolean[] shouldNotify = {false};
      lastLocationReminderAt.compute(outing.getId(), (id, lastSent) -> {
        if (lastSent == null
            || Duration.between(lastSent, now).compareTo(LOCATION_REMINDER_INTERVAL) >= 0) {
          shouldNotify[0] = true;
          return now;
        }
        return lastSent;
      });
      if (shouldNotify[0]) {
        notificationService.send(studentUserId,
            "도착 확인이 필요해요",
            "학교 반경 안에 계신 것 같아요. '도착' 버튼을 눌러주세요.",
            NotificationType.OUTING);
      }
    }
  }

  /**
   * 외출증의 위치/동선을 조회합니다(#97). 담당 선생님 본인, 또는 {@code DISCIPLINE}/
   * {@code ADMIN}만 조회할 수 있습니다(전체 {@code TEACHER}는 아님 — 위치는 상태보다 민감한
   * 정보라 {@link #validateDetailAccess}보다 좁게 가져간다).
   *
   * @param callerUserId 조회를 요청한 사용자 ID (Access Token에서 추출됨)
   * @param code         조회할 외출증의 외부 식별자 코드
   * @return 출발 좌표 → 위치 핑(시간순) → 도착 좌표 순으로 합성된 동선
   */
  @Transactional(readOnly = true)
  public OutingLocationsResponse getOutingLocations(Long callerUserId, String code) {
    Outing outing = outingRepository.findByCode(code)
        .orElseThrow(() -> new CustomException(OutingErrorCode.OUTING_NOT_FOUND));
    validateLocationAccess(callerUserId, outing);

    List<OutingLocationPointResponse> path = new ArrayList<>();
    if (outing.getDepartedAt() != null) {
      path.add(new OutingLocationPointResponse(
          outing.getDepartedLatitude(), outing.getDepartedLongitude(), outing.getDepartedAt()));
    }
    outingLocationRepository.findByOutingIdOrderByRecordedAtAscIdAsc(outing.getId()).forEach(
        location -> path.add(new OutingLocationPointResponse(
            location.getLatitude(), location.getLongitude(), location.getRecordedAt())));
    if (outing.getReturnedAt() != null) {
      path.add(new OutingLocationPointResponse(
          outing.getReturnedLatitude(), outing.getReturnedLongitude(), outing.getReturnedAt()));
    }
    // 핑 전송과 도착 보고가 거의 동시에 일어나면(#97 코드 리뷰 Medium 3번), 도착 좌표보다
    // recordedAt이 늦은 핑이 조회 시점엔 이미 저장돼 있을 수 있다 — 응답 직전에 한 번 더
    // recordedAt 기준으로 정렬해 "항상 오름차순" 불변식을 강제한다.
    path.sort(Comparator.comparing(OutingLocationPointResponse::recordedAt));

    return new OutingLocationsResponse(outing.getCode(), outing.getStatus(), path);
  }

  private void validateLocationAccess(Long callerUserId, Outing outing) {
    if (outing.getTeacher().getId().equals(callerUserId)) {
      return;
    }
    List<String> roles = userRoleRepository.findRoleCodesByUserId(callerUserId);
    if (roles.contains(DISCIPLINE_ROLE_CODE) || roles.contains(ADMIN_ROLE_CODE)) {
      return;
    }
    throw new CustomException(OutingErrorCode.ACCESS_DENIED);
  }

  /**
   * 출발/도착 보고 저장 중 낙관적 락 충돌이 나면 409({@code ALREADY_PROCESSED})로 변환한다
   * (#43 코드 리뷰 Medium 2번 대응). 학생이 네트워크 지연 중 버튼을 두 번 누르거나 클라이언트가
   * 재전송하면, 먼저 커밋된 요청이 이미 {@code status}를 바꿔놓은 뒤라 두 번째 저장은 버전
   * 충돌로 실패한다 — 이 상황은 원인 불명의 500이 아니라 "이미 처리된 요청"이라는 의미 있는
   * 409로 응답해야 한다. {@code save}가 아니라 {@code saveAndFlush}를 쓴다 — 일반
   * {@code save}는 실제 UPDATE를 트랜잭션 커밋 시점까지 지연시킬 수 있어, 버전 충돌이 이
   * 메서드의 {@code try} 밖(커밋 시점)에서 터져 그대로 500으로 새어나갈 수 있다(PR 코드
   * 리뷰에서 발견). {@code saveAndFlush}로 UPDATE를 즉시 실행시켜야 충돌을 이 자리에서
   * 확실히 잡는다.
   *
   * @param outing 저장할 외출증(출발/도착 필드가 이미 반영된 상태)
   */
  private void saveOrRejectAsAlreadyProcessed(Outing outing) {
    try {
      outingRepository.saveAndFlush(outing);
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new CustomException(OutingErrorCode.ALREADY_PROCESSED);
    }
  }

  private void validateOwnership(Long studentUserId, Outing outing) {
    if (!outing.getStudent().getId().equals(studentUserId)) {
      throw new CustomException(OutingErrorCode.ACCESS_DENIED);
    }
  }

  /**
   * 학교 운영시간({@link OutingTimeSlot#CUSTOM_WINDOW_START}~{@link
   * OutingTimeSlot#CUSTOM_WINDOW_END}) 안인지 검증한다(#43). 이 범위 밖에서는 외출증 상태와
   * 무관하게 "외출"이라는 개념 자체가 성립하지 않으므로 상태 검증보다 먼저 차단한다.
   *
   * @param now "지금" 시각(KST)
   */
  private void validateOperatingHours(LocalTime now) {
    if (now.isBefore(OutingTimeSlot.CUSTOM_WINDOW_START)
        || now.isAfter(OutingTimeSlot.CUSTOM_WINDOW_END)) {
      throw new CustomException(OutingErrorCode.OUTSIDE_OPERATING_HOURS);
    }
  }

  private void validateSchoolRadius(OutingLocationRequest request) {
    double distance = GeoUtils.distanceMeters(
        request.latitude(), request.longitude(),
        outingProperties.schoolLatitude(), outingProperties.schoolLongitude());
    if (distance > outingProperties.schoolRadiusMeters()) {
      throw new CustomException(OutingErrorCode.OUT_OF_SCHOOL_RADIUS);
    }
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
        outing.getRejectedReason(),
        outing.getDepartedAt(),
        outing.getReturnedAt(),
        isOffSchedule(outing));
  }

  /**
   * 출발/도착 보고가 이 외출증의 예정 시간대({@code outingDate}+{@code startTime}~
   * {@code endTime}) 밖에서 일어났는지 계산한다(#43). 별도 컬럼에 저장하지 않고 응답 변환
   * 시점마다 재계산한다 — 도착까지 끝났으면 도착 시각을, 아직 출발만 했으면 출발 시각을
   * 기준으로 판정한다. 경계값(정확히 {@code startTime}/{@code endTime})은 범위 안으로 본다.
   *
   * @param outing 판정할 외출증
   * @return 예정 시간대 밖에서 보고됐으면 {@code true}, 아직 출발 전이거나 시간대 안이면
   *         {@code false}
   */
  private boolean isOffSchedule(Outing outing) {
    LocalDateTime reportedAt =
        outing.getReturnedAt() != null ? outing.getReturnedAt() : outing.getDepartedAt();
    if (reportedAt == null) {
      return false;
    }
    LocalDateTime scheduledStart = outing.getOutingDate().atTime(outing.getStartTime());
    LocalDateTime scheduledEnd = outing.getOutingDate().atTime(outing.getEndTime());
    return reportedAt.isBefore(scheduledStart) || reportedAt.isAfter(scheduledEnd);
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

  /**
   * 외출증 단건 상세 조회 접근 권한을 검증합니다(#41 도입, #96에서 범위 확장). 신청 학생
   * 본인, 지정된 담당 선생님은 항상 통과한다. 그 외에는 {@code DISCIPLINE}/{@code ADMIN}
   * 또는 (담당 여부와 무관하게) {@code TEACHER} 역할이면 통과한다 — {@code /active}
   * 목록이 이미 전체 {@code TEACHER}에게 지금 외출 중인 전교생 현황을 보여주는 것과 인가
   * 범위를 맞추기 위해, 단건 조회도 담당 선생님으로 한정하지 않기로 확정했다(보스 확정,
   * 2026-08-25).
   */
  private void validateDetailAccess(Long callerUserId, Outing outing) {
    boolean isRelated = outing.getStudent().getId().equals(callerUserId)
        || outing.getTeacher().getId().equals(callerUserId);
    if (isRelated) {
      return;
    }
    List<String> roles = userRoleRepository.findRoleCodesByUserId(callerUserId);
    if (roles.contains(DISCIPLINE_ROLE_CODE)
        || roles.contains(ADMIN_ROLE_CODE)
        || roles.contains(TEACHER_ROLE_CODE)) {
      return;
    }
    throw new CustomException(OutingErrorCode.ACCESS_DENIED);
  }

  // applyOuting() 한 메서드 내부(시간 확정 → 겹침 체크) 계산에서만 쓰이고 API/도메인 경계를
  // 넘지 않아 private record로 남긴다 — 다른 곳에서도 필요해지면(예: 출발/도착) 그때 공용
  // 타입으로 승격한다.
  private record TimeRange(LocalTime start, LocalTime end) {}
}
