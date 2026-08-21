package com.remake.gone.schoolcamp.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.utils.GbswUtils;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.service.NotificationService;
import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.repository.OutingRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.schoolcamp.dto.SchoolCampApplicationResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampApplyRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampConflictingMemberResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampMemberRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampMemberResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampMyParticipationResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampMyParticipationSummaryResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampParticipationConflictResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.entity.SchoolCampApplication;
import com.remake.gone.schoolcamp.entity.SchoolCampMember;
import com.remake.gone.schoolcamp.entity.SchoolCampSession;
import com.remake.gone.schoolcamp.enums.SchoolCampMyRole;
import com.remake.gone.schoolcamp.enums.SchoolCampStatus;
import com.remake.gone.schoolcamp.exception.SchoolCampErrorCode;
import com.remake.gone.schoolcamp.repository.SchoolCampApplicationRepository;
import com.remake.gone.schoolcamp.repository.SchoolCampMemberRepository;
import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스쿨캠핑(SchoolCamp) 세션 등록/캘린더 조회/신청 비즈니스 로직을 처리하는 서비스.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchoolCampService {

  private static final DateTimeFormatter YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

  /** 스쿨캠핑을 열 수 없는 요일(금/토/일). */
  private static final Set<DayOfWeek> CLOSED_DAYS_OF_WEEK =
      Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

  /** 팀 최대 인원(대표 신청자 포함). */
  private static final int MAX_TEAM_SIZE = 8;

  private static final String TEACHER_ROLE_CODE = "TEACHER";

  /** 참여 내역 조회(#69) 페이지 크기 하한/상한 — {@code outing.OutingService}와 동일 기준. */
  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 100;

  private final SchoolCampSessionRepository sessionRepository;
  private final SchoolCampApplicationRepository applicationRepository;
  private final SchoolCampMemberRepository memberRepository;
  private final SchoolCampSessionClaimService sessionClaimService;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final NotificationService notificationService;
  private final OutingRepository outingRepository;
  private final SchoolCampWaitlistService waitlistService;

  /**
   * 다음 달 스쿨캠핑 가능 날짜를 일괄 등록합니다. 요일 검증/중복 검증 중 하나라도 위반하는
   * 날짜가 있으면 전체를 거부합니다(부분 성공 없음).
   *
   * @param campDates 등록할 날짜 목록({@code yyyyMMdd} 형식)
   * @return 등록된 세션 목록
   */
  @Transactional
  public List<SchoolCampSessionResponse> registerCampDates(List<String> campDates) {
    List<LocalDate> dates;
    try {
      dates = campDates.stream().map(date -> LocalDate.parse(date, YMD_FORMATTER)).toList();
    } catch (DateTimeParseException e) {
      // @Pattern은 8자리 숫자인지만 확인하고 실존하는 날짜인지는 보장하지 않는다(예:
      // "20261332", "20260230") — 여기서 걸러 400으로 응답한다.
      throw new CustomException(SchoolCampErrorCode.INVALID_CAMP_DATE);
    }

    boolean hasClosedDayOfWeek = dates.stream()
        .anyMatch(date -> CLOSED_DAYS_OF_WEEK.contains(date.getDayOfWeek()));
    if (hasClosedDayOfWeek) {
      throw new CustomException(SchoolCampErrorCode.INVALID_CAMP_DATE);
    }

    if (new HashSet<>(dates).size() != dates.size()) {
      // 요청 안에서 같은 날짜가 중복되면 DB UNIQUE 제약까지 안 가고 여기서 먼저 걸러
      // 도메인 에러 코드로 응답한다(그대로 두면 두 번째 삽입이 DataIntegrityViolationException으로
      // 실패해 범용 COMMON_006으로 응답되어 원인이 불분명해진다).
      throw new CustomException(SchoolCampErrorCode.CAMP_DATE_ALREADY_REGISTERED);
    }

    if (sessionRepository.existsByCampDateIn(dates)) {
      throw new CustomException(SchoolCampErrorCode.CAMP_DATE_ALREADY_REGISTERED);
    }

    List<SchoolCampSession> sessions = dates.stream()
        .map(date -> SchoolCampSession.builder().campDate(date).build())
        .toList();
    List<SchoolCampSession> saved = sessionRepository.saveAll(sessions);

    return saved.stream()
        .map(session -> new SchoolCampSessionResponse(
            session.getId(), session.getCampDate().format(YMD_FORMATTER)))
        .toList();
  }

  /**
   * 특정 달의 스쿨캠핑 캘린더(날짜별 점유 상태)를 조회합니다. 점유된 세션마다 그 세션의
   * 활성 신청에서 담당 선생님/대표 신청자 표시 이름을 채운다.
   *
   * <p>점유된 세션의 활성 신청을 세션 수만큼 개별 조회하지 않고, 점유된 세션 ID를 모아
   * {@link SchoolCampApplicationRepository#findBySessionIdInAndCancelledAtIsNull} 한 번으로
   * 배치 조회한다(N+1 방지) — 코드 리뷰(#68) 지적 사항.
   *
   * @param month 조회할 달
   * @return 그 달의 세션별 캘린더 정보
   */
  @Transactional(readOnly = true)
  public List<SchoolCampCalendarResponse> getCalendar(YearMonth month) {
    List<SchoolCampSession> sessions =
        sessionRepository.findByCampDateBetween(month.atDay(1), month.atEndOfMonth());

    List<Long> takenSessionIds = sessions.stream()
        .filter(session -> session.getTakenAt() != null)
        .map(SchoolCampSession::getId)
        .toList();
    Map<Long, SchoolCampApplication> applicationsBySessionId = takenSessionIds.isEmpty()
        ? Map.of()
        : applicationRepository.findBySessionIdInAndCancelledAtIsNull(takenSessionIds).stream()
            .collect(Collectors.toMap(
                application -> application.getSession().getId(), application -> application));

    return sessions.stream()
        .map(session -> toCalendarResponse(session, applicationsBySessionId.get(session.getId())))
        .toList();
  }

  /**
   * 점유되지 않은 세션은 즉시 {@code OPEN}으로 반환한다. 점유된 세션인데 활성 신청이 없으면
   * (claim 이후 release 실패로 남은 "유령 점유" — {@code 68-schoolcamp-application.md}
   * "잔여 리스크" 참고) 예외로 전체 요청을 실패시키지 않고, 그 세션 하나만 이름 없이
   * {@code CLOSED}로 방어적으로 채운 뒤 경고 로그를 남긴다 — 코드 리뷰(#68)에서 지적된 대로,
   * 여기서 예외를 던지면 그 세션 하나가 아니라 이 메서드가 처리하던 스트림 전체가 중단되어
   * 그 달의 캘린더 조회 자체가 500으로 막힌다.
   */
  private SchoolCampCalendarResponse toCalendarResponse(
      SchoolCampSession session, SchoolCampApplication application) {
    if (session.getTakenAt() == null) {
      return new SchoolCampCalendarResponse(
          session.getId(), session.getCampDate().format(YMD_FORMATTER),
          SchoolCampStatus.OPEN, null, null);
    }

    if (application == null) {
      log.warn("점유된 세션에 활성 신청이 없습니다(유령 점유 의심): sessionId={}", session.getId());
      return new SchoolCampCalendarResponse(
          session.getId(), session.getCampDate().format(YMD_FORMATTER),
          SchoolCampStatus.CLOSED, null, null);
    }

    return new SchoolCampCalendarResponse(
        session.getId(),
        session.getCampDate().format(YMD_FORMATTER),
        SchoolCampStatus.CLOSED,
        teacherDisplayName(application),
        applicantDisplayName(application.getApplicant().getGbsw()));
  }

  /**
   * 스쿨캠핑을 신청합니다(선착순, 팀 단위). 이 엔드포인트의 핵심은 "그 날짜를 딱 한 팀만
   * 차지하게 지키는" 것 — DB 조회가 필요 없는 형식 검증만 먼저 하고 곧바로 세션을 점유해
   * 대부분의 탈락자를 그 자리에서 걸러낸 뒤, DB 조회가 필요한 무거운 검증(선생님 역할·팀원
   * 존재·월 중복)은 그 순간의 당첨자 한 명에게만 수행한다(상세 근거:
   * {@code docs/domain/schoolcamp/68-schoolcamp-application.md} "구현 로직" 절).
   *
   * <p>점유(claim) 이후 이 메서드의 나머지 로직이 실패하면 {@link SchoolCampSessionClaimService
   * #release}를 명시적으로 호출해 세션을 되돌린다 — claim이 별도 트랜잭션으로 이미 커밋되어
   * 있어 이 메서드 자신의 트랜잭션 롤백만으로는 되돌아가지 않기 때문이다.
   *
   * @param applicantUserId 신청하는 대표 학생 사용자 ID(Access Token에서 추출됨)
   * @param sessionId       신청할 세션의 PK
   * @param request         신청 요청 정보
   * @param now             점유 시각으로 기록할 "지금"
   * @return 생성된 신청 정보
   */
  @Transactional
  public SchoolCampApplicationResponse applyToCamp(
      Long applicantUserId, Long sessionId, SchoolCampApplyRequest request, LocalDateTime now) {

    SchoolCampSession session = sessionRepository.findById(sessionId)
        .orElseThrow(() -> new CustomException(SchoolCampErrorCode.SESSION_NOT_FOUND));

    List<SchoolCampMemberRequest> additionalMembers =
        request.additionalMembers() == null ? List.of() : request.additionalMembers();
    validateApplicationFormat(request, additionalMembers);

    boolean claimed = sessionClaimService.claim(sessionId, now);
    if (!claimed) {
      throw new CustomException(SchoolCampErrorCode.SESSION_ALREADY_TAKEN);
    }

    try {
      return completeApplication(applicantUserId, session, request, additionalMembers);
    } catch (RuntimeException e) {
      releaseQuietly(sessionId, e);
      throw e;
    }
  }

  /**
   * claim 이후 검증 실패 시 세션을 반환한다. {@code release} 호출 자체가 실패해도(DB
   * 순단 등, 기획서 "잔여 리스크" 참고) 원래 실패 원인({@code originalFailure})을 가리지
   * 않고 그대로 다시 던질 수 있도록, release 실패는 로그로만 남기고 삼킨다 — 코드
   * 리뷰(#68) 지적 사항.
   */
  private void releaseQuietly(Long sessionId, RuntimeException originalFailure) {
    try {
      sessionClaimService.release(sessionId);
    } catch (RuntimeException releaseFailure) {
      log.error("claim 이후 실패 처리 중 release마저 실패했습니다: sessionId={}, "
          + "원래 실패 원인={}", sessionId, originalFailure.toString(), releaseFailure);
    }
  }

  /**
   * 본인 신청을 취소합니다(#70). 취소된 신청은 {@code cancelled_at}만 채우고(hard delete
   * 아님) 세션을 다시 빈 날짜로 되돌린다.
   *
   * <p>세션 반환은 {@link SchoolCampSessionClaimService#release}(REQUIRES_NEW)를 거치지
   * 않고 {@code sessionRepository.release}를 이 메서드 자신의 트랜잭션 안에서 직접
   * 호출한다 — 취소는 그 신청의 유일한 소유자만 호출할 수 있어(아래 소유권 확인이 이미 그
   * 사실을 보장) 애초에 경합 상대가 없다. 오히려 {@code cancelledAt} 갱신과 {@code taken_at}
   * 반환을 같은 트랜잭션으로 묶어야, 이후 어떤 이유로든 커밋이 실패했을 때 두 변경이 함께
   * 롤백돼 "신청은 취소됐는데 세션은 여전히 잠긴" 불일치가 생기지 않는다 — claim/release가
   * REQUIRES_NEW라서 안았던 "release 실패 시 유령 점유" 잔여 리스크가 이 메서드에는 없다.
   *
   * <p>세션 반환 직후 그 세션이 속한 달의 대기자 전원에게 "자리가 났어요" 알림을
   * 발송한다({@code SchoolCampWaitlistService#notifyForMonth}, #83). 같은 트랜잭션 안에서
   * 실행되어, 취소 자체가 롤백되면 알림 저장도 함께 롤백된다.
   *
   * @param applicantUserId 취소를 요청한 사용자 ID(Access Token에서 추출됨)
   * @param applicationId   취소할 신청의 PK
   * @param now             "지금"(KST) — 당일 취소 금지 판단과 {@code cancelledAt} 기록에 사용
   */
  @Transactional
  public void cancelApplication(Long applicantUserId, Long applicationId, LocalDateTime now) {
    SchoolCampApplication application =
        applicationRepository.findByIdAndCancelledAtIsNull(applicationId)
            .orElseThrow(() -> new CustomException(SchoolCampErrorCode.APPLICATION_NOT_FOUND));

    if (!application.getApplicant().getId().equals(applicantUserId)) {
      throw new CustomException(SchoolCampErrorCode.NOT_APPLICATION_OWNER);
    }
    if (application.getSession().getCampDate().equals(now.toLocalDate())) {
      throw new CustomException(SchoolCampErrorCode.CANCEL_NOT_ALLOWED_ON_CAMP_DAY);
    }

    application.setCancelledAt(now);
    applicationRepository.save(application);
    sessionRepository.release(application.getSession().getId());
    waitlistService.notifyForMonth(YearMonth.from(application.getSession().getCampDate()));
  }

  /**
   * 담당 선생님/팀원 정보를 수정합니다(#70). {@code additionalMembers}는 전체 교체
   * 방식이다 — 대표 신청자를 제외한 최종 팀원 목록 전체를 받아 기존 팀원과 비교(diff)해서,
   * 새로 추가되는 학생만 이번 달 중복 참여를 재확인하고 빠진 학생/기존 "기타" 팀원의 행만
   * 삭제한다. "기타"(자유 입력) 팀원은 안정적인 식별자가 없어 diff 대상이 아니다 — 기존
   * guest 행은 전부 삭제하고 요청받은 목록으로 전부 새로 삽입한다. 세션의 {@code taken_at}은
   * 건드리지 않는다 — 그 날짜는 이미 이 신청 하나가 통째로 차지한 상태라 별도 정원 조작이
   * 필요 없다.
   *
   * @param applicantUserId 수정을 요청한 사용자 ID(Access Token에서 추출됨)
   * @param applicationId   수정할 신청의 PK
   * @param request         새 담당 선생님/팀원 스냅샷
   * @return 갱신된 신청 정보
   */
  @Transactional
  public SchoolCampApplicationResponse updateApplication(
      Long applicantUserId, Long applicationId, SchoolCampApplyRequest request) {

    SchoolCampApplication application =
        applicationRepository.findByIdAndCancelledAtIsNull(applicationId)
            .orElseThrow(() -> new CustomException(SchoolCampErrorCode.APPLICATION_NOT_FOUND));
    if (!application.getApplicant().getId().equals(applicantUserId)) {
      throw new CustomException(SchoolCampErrorCode.NOT_APPLICATION_OWNER);
    }

    List<SchoolCampMemberRequest> additionalMembers =
        request.additionalMembers() == null ? List.of() : request.additionalMembers();
    validateApplicationFormat(request, additionalMembers);

    final User teacher =
        request.teacherUserId() != null ? findValidTeacher(request.teacherUserId()) : null;
    Map<Long, User> studentsById = findExistingStudents(applicantUserId, additionalMembers);

    List<SchoolCampMember> existingMembers = memberRepository.findByApplicationId(applicationId);
    MemberDiff diff = computeMemberDiff(existingMembers, studentsById.keySet());

    if (!diff.addedStudentIds().isEmpty()) {
      validateNoDuplicateThisMonth(
          diff.addedStudentIds(), studentsById, application.getSession().getCampDate());
    }
    if (!diff.memberIdsToDelete().isEmpty()) {
      memberRepository.deleteAllById(diff.memberIdsToDelete());
    }

    List<SchoolCampMember> newMembers =
        buildNewMembers(application, additionalMembers, studentsById, diff.addedStudentIds());
    if (!newMembers.isEmpty()) {
      // 동시에 처리된 다른 PATCH가 같은 학생을 이미 팀원으로 추가해 (application_id,
      // student_user_id) 유니크 제약(V13)을 위반하면, 범용 500(COMMON_006) 대신 재시도
      // 가능한 충돌임을 명시적으로 알린다(코드 리뷰 #70 1번 대응).
      try {
        memberRepository.saveAll(newMembers);
      } catch (DataIntegrityViolationException e) {
        throw new CustomException(SchoolCampErrorCode.CONCURRENT_UPDATE_CONFLICT);
      }
    }

    application.setTeacherUser(teacher);
    application.setTeacherName(teacher == null ? request.teacherName() : null);
    applicationRepository.save(application);

    sendInviteNotifications(application.getApplicant(), application.getSession(), newMembers);

    List<SchoolCampMember> finalMembers = new ArrayList<>();
    finalMembers.add(diff.applicantMember());
    finalMembers.addAll(diff.keptMembers());
    finalMembers.addAll(newMembers);

    return toApplicationResponse(application, teacher, application.getSession(), finalMembers);
  }

  /**
   * 오늘 스쿨캠핑에 참여하는 학생 중, 아직 점심({@link OutingTimeSlot#LUNCH}) 시간대를
   * 포함하는 외출증을 신청하지 않은 사람에게 리마인더 알림을 저장합니다(#71).
   *
   * <p>대상은 대표 신청자뿐 아니라 {@link SchoolCampMember} 전체다 — 누가 실제로 장을 보러
   * 가는지 구분하는 데이터가 없어 과다 알림 쪽으로 판단했다. 계정이 없는 "기타"(자유 입력)
   * 팀원은 보낼 곳이 없어 대상에서 제외한다. 외출증 신청 여부는 {@link OutingStatus
   * #ACTIVE_STATUSES}(승인 여부가 아니라 신청 여부)로 판단한다 — {@code REJECTED}는 다시
   * 알림 대상이 되어 재신청을 유도한다.
   *
   * <p>대상 인원이 하루 최대 8명으로 상한이 명확해, {@code outing}(#42)처럼 건별 독립
   * 트랜잭션으로 쪼개지 않고 하나의 트랜잭션으로 처리한다 — 이 메서드는 기존 데이터를
   * 수정하지 않고 새 {@code Notification} 행을 추가만 하므로 경합할 다른 트랜잭션이 없고,
   * 오히려 하나로 묶어야 실패 시 "일부만 알림 받음" 같은 불완전한 상태를 피할 수 있다.
   *
   * @param today 리마인더를 보낼 기준 날짜(KST) — 스케줄러가 "지금"을 전달한다
   */
  @Transactional
  public void sendOutingReminders(LocalDate today) {
    applicationRepository.findBySessionCampDateAndCancelledAtIsNull(today)
        .forEach(application -> remindMembers(application, today));
  }

  private void remindMembers(SchoolCampApplication application, LocalDate today) {
    memberRepository.findByApplicationId(application.getId()).stream()
        .filter(member -> member.getStudentUser() != null)
        .forEach(member -> remindIfNoLunchOuting(member.getStudentUser().getId(), today));
  }

  /**
   * 시간대 이름({@code timeSlot} 컬럼)이 아니라 실제 시작/종료 시각으로 점심시간 포함
   * 여부를 판단한다(코드 리뷰 #71 대응) — {@code CUSTOM} 외출증은 {@code LUNCH} 프리셋
   * 범위(12:30~13:40)를 포함하는 더 넓은 시간대(08:40~20:30)를 자유롭게 신청할 수 있어,
   * {@code timeSlot == LUNCH}로만 판단하면 점심시간을 실제로 포함하는 {@code CUSTOM}
   * 외출증을 놓치고 리마인더를 잘못 보낸다.
   */
  private void remindIfNoLunchOuting(Long studentId, LocalDate today) {
    boolean hasLunchOuting = outingRepository
        .findByStudentIdAndOutingDateAndStatusIn(studentId, today, OutingStatus.ACTIVE_STATUSES)
        .stream()
        .anyMatch(SchoolCampService::overlapsLunch);
    if (!hasLunchOuting) {
      notificationService.send(studentId, "오늘 스쿨캠핑이 있어요!",
          "장 보러 갈 외출증은 받으셨나요? 점심시간에 미리 신청해보세요.",
          NotificationType.SCHOOLCAMP);
    }
  }

  private static boolean overlapsLunch(Outing outing) {
    return !outing.getStartTime().isAfter(OutingTimeSlot.LUNCH.getEndTime())
        && !outing.getEndTime().isBefore(OutingTimeSlot.LUNCH.getStartTime());
  }

  /**
   * 본인(대표/팀원/담당 선생님 중 하나)이 관련된 스쿨캠핑 이력을 목록으로 조회합니다(#69).
   * 취소된 신청도 포함한다 — 유효 여부는 응답의 {@code cancelledAt}으로 구분한다.
   *
   * <p>팀원 전체는 담지 않는 요약 응답이다 — 상세는 {@link #getMyParticipationDetail}로
   * 따로 조회한다(컬렉션 안에 컬렉션을 중첩하지 않는다는 API 설계 원칙 대응).
   *
   * @param userId 조회하는 사용자 ID(Access Token에서 추출됨, 학생 또는 선생님)
   * @param month  범위를 한정할 달. {@code null}이면 전체 이력
   * @param page   페이지 번호(0부터 시작)
   * @param size   페이지 크기(1~100)
   * @return 페이지네이션된 참여 내역 요약 목록, {@code campDate} 내림차순
   */
  @Transactional(readOnly = true)
  public PageResponse<SchoolCampMyParticipationSummaryResponse> getMyParticipations(
      Long userId, YearMonth month, int page, int size) {
    validatePageParams(page, size);

    List<ParticipationSource> sources = collectMyParticipationSources(userId, month);

    PageResponse<ParticipationSource> paged = PageResponse.of(sources, page, size);
    List<SchoolCampMyParticipationSummaryResponse> content = paged.content().stream()
        .map(this::toMyParticipationSummary)
        .toList();
    return new PageResponse<>(
        content, paged.page(), paged.size(), paged.totalElements(), paged.totalPages(),
        paged.hasNext());
  }

  /**
   * 본인이 학생(대표/팀원)으로 참여한 신청과 담당 선생님으로 지정된 신청을 각각 조회해
   * {@code campDate} 내림차순 하나로 합친다(#69, 설계 변경 3). 같은 사람이 두 역할을
   * 동시에 가지는 경우는 실질적으로 없지만(담당 선생님 지정은 {@code TEACHER} 역할
   * 보유자만 가능), 있더라도 시간순으로 자연스럽게 섞이도록 별도 방어 없이 병합·정렬만
   * 한다.
   */
  private List<ParticipationSource> collectMyParticipationSources(Long userId, YearMonth month) {
    List<SchoolCampMember> myMemberRows = month != null
        ? memberRepository.findMyParticipationsInMonth(
            userId, month.atDay(1), month.atEndOfMonth())
        : memberRepository.findMyParticipations(userId);
    List<SchoolCampApplication> myTeacherApplications = month != null
        ? applicationRepository.findByTeacherUserIdInMonth(
            userId, month.atDay(1), month.atEndOfMonth())
        : applicationRepository.findByTeacherUserId(userId);

    Stream<ParticipationSource> studentSources = myMemberRows.stream()
        .map(row -> new ParticipationSource(row.getApplication(), toMyRole(row)));
    Stream<ParticipationSource> teacherSources = myTeacherApplications.stream()
        .map(application -> new ParticipationSource(application, SchoolCampMyRole.TEACHER));

    return Stream.concat(studentSources, teacherSources)
        .sorted(Comparator.comparing(
                (ParticipationSource source) -> source.application().getSession().getCampDate())
            .reversed())
        .toList();
  }

  /** {@link #collectMyParticipationSources}가 만드는, 참여 신청 1건 + 그 안에서의 내 역할. */
  private record ParticipationSource(SchoolCampApplication application, SchoolCampMyRole role) {}

  private void validatePageParams(int page, int size) {
    if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
      throw new CustomException(SchoolCampErrorCode.INVALID_PAGE_PARAMS);
    }
  }

  private SchoolCampMyParticipationSummaryResponse toMyParticipationSummary(
      ParticipationSource source) {
    SchoolCampApplication application = source.application();
    return new SchoolCampMyParticipationSummaryResponse(
        application.getId(),
        application.getSession().getCampDate().format(YMD_FORMATTER),
        teacherDisplayName(application),
        source.role(),
        application.getAppliedAt().toString(),
        application.getCancelledAt() != null ? application.getCancelledAt().toString() : null);
  }

  /**
   * 본인이 참여한 신청 1건의 상세를 조회합니다(#69). 취소된 신청도 조회할 수 있다.
   *
   * @param userId        조회하는 사용자 ID(Access Token에서 추출됨, 학생 또는 선생님)
   * @param applicationId 조회할 신청의 PK
   * @return 팀원 전체를 포함한 참여 내역 상세
   */
  @Transactional(readOnly = true)
  public SchoolCampMyParticipationResponse getMyParticipationDetail(
      Long userId, Long applicationId) {
    SchoolCampApplication application = applicationRepository.findById(applicationId)
        .orElseThrow(() -> new CustomException(SchoolCampErrorCode.APPLICATION_NOT_FOUND));

    List<SchoolCampMember> members = memberRepository.findByApplicationId(applicationId);
    SchoolCampMyRole myRole = members.stream()
        .filter(member -> member.getStudentUser() != null
            && member.getStudentUser().getId().equals(userId))
        .findFirst()
        .map(this::toMyRole)
        .orElseGet(() -> resolveTeacherRole(application, userId));

    return new SchoolCampMyParticipationResponse(
        application.getId(),
        application.getSession().getCampDate().format(YMD_FORMATTER),
        teacherDisplayName(application),
        myRole,
        members.stream().map(this::toMemberResponse).toList(),
        application.getAppliedAt().toString(),
        application.getCancelledAt() != null ? application.getCancelledAt().toString() : null);
  }

  private SchoolCampMyRole resolveTeacherRole(SchoolCampApplication application, Long userId) {
    boolean isTeacher = application.getTeacherUser() != null
        && application.getTeacherUser().getId().equals(userId);
    if (!isTeacher) {
      throw new CustomException(SchoolCampErrorCode.NOT_APPLICATION_PARTICIPANT);
    }
    return SchoolCampMyRole.TEACHER;
  }

  private SchoolCampMyRole toMyRole(SchoolCampMember myRow) {
    return myRow.isApplicant() ? SchoolCampMyRole.APPLICANT : SchoolCampMyRole.MEMBER;
  }

  /**
   * {@link #updateApplication}이 기존 팀원과 요청받은 팀원 스냅샷을 비교(diff)한 결과.
   *
   * @param applicantMember   대표 신청자 팀원 행(항상 유지, diff 대상 아님)
   * @param keptMembers       그대로 유지되는 기존 팀원(가입 학생만 — "기타"는 항상 diff 대상)
   * @param addedStudentIds   새로 추가되는 학생 ID(이번 달 중복 재확인 대상)
   * @param memberIdsToDelete 삭제할 팀원 행 PK(빠진 학생 + 기존 "기타" 전체)
   */
  private record MemberDiff(
      SchoolCampMember applicantMember,
      List<SchoolCampMember> keptMembers,
      Set<Long> addedStudentIds,
      List<Long> memberIdsToDelete) {}

  private MemberDiff computeMemberDiff(
      List<SchoolCampMember> existingMembers, Set<Long> newStudentIds) {
    SchoolCampMember applicantMember = existingMembers.stream()
        .filter(SchoolCampMember::isApplicant)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("신청에 대표 신청자 팀원 행이 없습니다."));

    List<SchoolCampMember> existingNonApplicantMembers = existingMembers.stream()
        .filter(member -> !member.isApplicant())
        .toList();

    Set<Long> existingStudentIds = existingNonApplicantMembers.stream()
        .filter(member -> member.getStudentUser() != null)
        .map(member -> member.getStudentUser().getId())
        .collect(Collectors.toSet());

    Set<Long> addedStudentIds = new HashSet<>(newStudentIds);
    addedStudentIds.removeAll(existingStudentIds);

    List<SchoolCampMember> keptMembers = existingNonApplicantMembers.stream()
        .filter(member -> member.getStudentUser() != null
            && newStudentIds.contains(member.getStudentUser().getId()))
        .toList();

    List<Long> memberIdsToDelete = existingNonApplicantMembers.stream()
        .filter(member -> member.getStudentUser() == null
            || !newStudentIds.contains(member.getStudentUser().getId()))
        .map(SchoolCampMember::getId)
        .toList();

    return new MemberDiff(applicantMember, keptMembers, addedStudentIds, memberIdsToDelete);
  }

  private List<SchoolCampMember> buildNewMembers(
      SchoolCampApplication application, List<SchoolCampMemberRequest> additionalMembers,
      Map<Long, User> studentsById, Set<Long> addedStudentIds) {
    List<SchoolCampMember> newMembers = new ArrayList<>();
    for (SchoolCampMemberRequest memberRequest : additionalMembers) {
      if (memberRequest.studentUserId() != null) {
        if (addedStudentIds.contains(memberRequest.studentUserId())) {
          newMembers.add(SchoolCampMember.builder()
              .application(application)
              .studentUser(studentsById.get(memberRequest.studentUserId()))
              .applicant(false)
              .build());
        }
      } else {
        newMembers.add(SchoolCampMember.builder()
            .application(application)
            .guestName(memberRequest.guestName())
            .applicant(false)
            .build());
      }
    }
    return newMembers;
  }

  private void validateApplicationFormat(
      SchoolCampApplyRequest request, List<SchoolCampMemberRequest> additionalMembers) {
    boolean hasTeacherUserId = request.teacherUserId() != null;
    boolean hasTeacherName = request.teacherName() != null && !request.teacherName().isBlank();
    if (hasTeacherUserId == hasTeacherName) {
      // 둘 다 없거나 둘 다 있으면 위반(정확히 하나만 허용).
      throw new CustomException(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT);
    }

    for (SchoolCampMemberRequest member : additionalMembers) {
      boolean hasStudentUserId = member.studentUserId() != null;
      boolean hasGuestName = member.guestName() != null && !member.guestName().isBlank();
      if (hasStudentUserId == hasGuestName) {
        throw new CustomException(SchoolCampErrorCode.INVALID_MEMBER_INFO);
      }
    }

    int memberCount = 1 + additionalMembers.size();
    if (memberCount > MAX_TEAM_SIZE) {
      throw new CustomException(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT);
    }
  }

  private SchoolCampApplicationResponse completeApplication(
      Long applicantUserId, SchoolCampSession session, SchoolCampApplyRequest request,
      List<SchoolCampMemberRequest> additionalMembers) {

    User applicant = userRepository.findById(applicantUserId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));
    User teacher =
        request.teacherUserId() != null ? findValidTeacher(request.teacherUserId()) : null;

    Map<Long, User> studentsById =
        validateAndCollectStudents(applicantUserId, applicant, additionalMembers, session);

    SchoolCampApplication application = SchoolCampApplication.builder()
        .session(session)
        .applicant(applicant)
        .teacherUser(teacher)
        .teacherName(teacher == null ? request.teacherName() : null)
        .build();
    applicationRepository.save(application);

    List<SchoolCampMember> members =
        buildMembers(application, applicant, additionalMembers, studentsById);
    memberRepository.saveAll(members);

    sendInviteNotifications(applicant, session, members);

    return toApplicationResponse(application, teacher, session, members);
  }

  /**
   * {@link #completeApplication}(#68 신규 신청) 전용 — 팀원 존재 확인 후, 대표 신청자
   * 본인까지 포함한 후보 전체로 이번 달 중복 참여를 검사한다. 반환하는 맵은
   * {@link #buildMembers}가 팀원 엔티티를 만들 때 그대로 재사용한다.
   */
  private Map<Long, User> validateAndCollectStudents(
      Long applicantUserId, User applicant, List<SchoolCampMemberRequest> additionalMembers,
      SchoolCampSession session) {
    Map<Long, User> studentsById = findExistingStudents(applicantUserId, additionalMembers);
    Set<Long> candidateIds = new HashSet<>(studentsById.keySet());
    candidateIds.add(applicantUserId);
    Map<Long, User> usersById = new HashMap<>(studentsById);
    usersById.put(applicantUserId, applicant);
    validateNoDuplicateThisMonth(candidateIds, usersById, session.getCampDate());
    return studentsById;
  }

  private User findValidTeacher(Long teacherUserId) {
    // outing 도메인의 OutingService.findValidTeacher와 동일 패턴.
    User teacher = userRepository.findById(teacherUserId)
        .orElseThrow(() -> new CustomException(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT));
    List<String> roles = userRoleRepository.findRoleCodesByUserId(teacherUserId);
    if (!roles.contains(TEACHER_ROLE_CODE)) {
      throw new CustomException(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT);
    }
    return teacher;
  }

  private Map<Long, User> findExistingStudents(
      Long applicantUserId, List<SchoolCampMemberRequest> additionalMembers) {
    List<Long> candidateIds = additionalMembers.stream()
        .map(SchoolCampMemberRequest::studentUserId)
        .filter(id -> id != null)
        .toList();

    if (new HashSet<>(candidateIds).size() != candidateIds.size()) {
      // registerCampDates(날짜 중복 검증)와 동일하게 IN 절의 암묵적 dedupe에 기대지 않고
      // 명시적으로 검사한다.
      throw new CustomException(SchoolCampErrorCode.INVALID_MEMBER_INFO);
    }
    if (candidateIds.contains(applicantUserId)) {
      throw new CustomException(SchoolCampErrorCode.INVALID_MEMBER_INFO);
    }

    List<User> found = userRepository.findAllById(candidateIds);
    if (found.size() != candidateIds.size()) {
      throw new CustomException(SchoolCampErrorCode.INVALID_MEMBER_INFO);
    }

    Map<Long, User> byId = new HashMap<>();
    found.forEach(user -> byId.put(user.getId(), user));
    return byId;
  }

  /**
   * 후보 학생 중 이번 달에 이미(대표/팀원 구분 없이) 참여 중인 사람이 있으면 거부한다.
   * 후보 집합은 호출부가 직접 구성한다 — {@link #applyToCamp}(신규 신청)는 대표 신청자
   * 본인을 항상 포함해야 하지만, {@link #updateApplication}(#70, 새로 추가되는 팀원만
   * 재검사)은 포함하면 안 된다(대표 신청자는 이미 이 신청으로 참여 중이라 넣으면 항상
   * 걸린다) — 그래서 이 메서드가 자동으로 넣지 않는다.
   *
   * <p>거부할 때는 어떤 학생 때문에 걸렸는지를 {@link CustomException#getData()}에 실어
   * 던진다(#81) — {@code usersById}는 호출부가 이미 조회해둔 후보 학생의 {@link User}
   * 맵이라, 이 정보를 채우려고 추가 쿼리를 하지 않는다.
   *
   * @param candidateIds 확인할 학생 ID 후보
   * @param usersById    {@code candidateIds}에 대응하는 {@link User} 맵(걸렸을 때 실명/
   *                     학년/반을 응답에 채우는 용도)
   * @param campDate     확인할 세션의 캠핑 날짜(이 날짜가 속한 달을 기준으로 검사)
   */
  private void validateNoDuplicateThisMonth(
      Set<Long> candidateIds, Map<Long, User> usersById, LocalDate campDate) {
    YearMonth month = YearMonth.from(campDate);
    List<Long> participated = memberRepository.findParticipatedStudentIdsInMonth(
        candidateIds, month.atDay(1), month.atEndOfMonth());
    if (!participated.isEmpty()) {
      List<SchoolCampConflictingMemberResponse> conflictingMembers = participated.stream()
          .map(studentId -> toConflictingMemberResponse(usersById.get(studentId)))
          .toList();
      throw new CustomException(
          SchoolCampErrorCode.ALREADY_PARTICIPATED_THIS_MONTH,
          new SchoolCampParticipationConflictResponse(conflictingMembers));
    }
  }

  private SchoolCampConflictingMemberResponse toConflictingMemberResponse(User user) {
    Gbsw gbsw = user.getGbsw();
    return new SchoolCampConflictingMemberResponse(
        user.getId(), gbsw.getName(), gbsw.getGrade(), gbsw.getClassNo());
  }

  private List<SchoolCampMember> buildMembers(
      SchoolCampApplication application, User applicant,
      List<SchoolCampMemberRequest> additionalMembers, Map<Long, User> studentsById) {
    List<SchoolCampMember> members = new ArrayList<>();
    members.add(SchoolCampMember.builder()
        .application(application)
        .studentUser(applicant)
        .applicant(true)
        .build());

    for (SchoolCampMemberRequest memberRequest : additionalMembers) {
      if (memberRequest.studentUserId() != null) {
        members.add(SchoolCampMember.builder()
            .application(application)
            .studentUser(studentsById.get(memberRequest.studentUserId()))
            .applicant(false)
            .build());
      } else {
        members.add(SchoolCampMember.builder()
            .application(application)
            .guestName(memberRequest.guestName())
            .applicant(false)
            .build());
      }
    }
    return members;
  }

  private void sendInviteNotifications(
      User applicant, SchoolCampSession session, List<SchoolCampMember> members) {
    String dateLabel = "%d월 %d일".formatted(
        session.getCampDate().getMonthValue(), session.getCampDate().getDayOfMonth());
    members.stream()
        .filter(member -> !member.isApplicant() && member.getStudentUser() != null)
        .forEach(member -> notificationService.send(
            member.getStudentUser().getId(),
            "스쿨캠핑에 초대되었어요",
            "%s님이 %s 스쿨캠핑에 초대했어요.".formatted(applicant.getName(), dateLabel),
            NotificationType.SCHOOLCAMP));
  }

  private SchoolCampApplicationResponse toApplicationResponse(
      SchoolCampApplication application, User teacher, SchoolCampSession session,
      List<SchoolCampMember> members) {
    String teacherDisplayName =
        teacher != null ? teacher.getGbsw().getName() : application.getTeacherName();
    List<SchoolCampMemberResponse> memberResponses =
        members.stream().map(this::toMemberResponse).toList();

    return new SchoolCampApplicationResponse(
        application.getId(),
        session.getCampDate().format(YMD_FORMATTER),
        teacherDisplayName,
        memberResponses,
        application.getAppliedAt().toString());
  }

  private SchoolCampMemberResponse toMemberResponse(SchoolCampMember member) {
    if (member.getStudentUser() != null) {
      Gbsw gbsw = member.getStudentUser().getGbsw();
      return new SchoolCampMemberResponse(
          gbsw.getName(), gbsw.getGrade(), gbsw.getClassNo(), null, member.isApplicant());
    }
    return new SchoolCampMemberResponse(
        null, null, null, member.getGuestName(), member.isApplicant());
  }

  private String teacherDisplayName(SchoolCampApplication application) {
    return application.getTeacherUser() != null
        ? application.getTeacherUser().getGbsw().getName()
        : application.getTeacherName();
  }

  private String applicantDisplayName(Gbsw applicantGbsw) {
    return GbswUtils.studentNumber(applicantGbsw) + applicantGbsw.getName();
  }
}
