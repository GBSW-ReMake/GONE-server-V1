package com.remake.gone.schoolcamp.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.utils.GbswUtils;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.service.NotificationService;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.schoolcamp.dto.SchoolCampApplicationResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampApplyRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampMemberRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampMemberResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.entity.SchoolCampApplication;
import com.remake.gone.schoolcamp.entity.SchoolCampMember;
import com.remake.gone.schoolcamp.entity.SchoolCampSession;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스쿨캠핑(SchoolCamp) 세션 등록/캘린더 조회/신청 비즈니스 로직을 처리하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class SchoolCampService {

  private static final DateTimeFormatter YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

  /** 스쿨캠핑을 열 수 없는 요일(금/토/일). */
  private static final Set<DayOfWeek> CLOSED_DAYS_OF_WEEK =
      Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

  /** 팀 최대 인원(대표 신청자 포함). */
  private static final int MAX_TEAM_SIZE = 8;

  private static final String TEACHER_ROLE_CODE = "TEACHER";

  private final SchoolCampSessionRepository sessionRepository;
  private final SchoolCampApplicationRepository applicationRepository;
  private final SchoolCampMemberRepository memberRepository;
  private final SchoolCampSessionClaimService sessionClaimService;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final NotificationService notificationService;

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
   * @param month 조회할 달
   * @return 그 달의 세션별 캘린더 정보
   */
  @Transactional(readOnly = true)
  public List<SchoolCampCalendarResponse> getCalendar(YearMonth month) {
    List<SchoolCampSession> sessions =
        sessionRepository.findByCampDateBetween(month.atDay(1), month.atEndOfMonth());

    return sessions.stream().map(this::toCalendarResponse).toList();
  }

  private SchoolCampCalendarResponse toCalendarResponse(SchoolCampSession session) {
    if (session.getTakenAt() == null) {
      return new SchoolCampCalendarResponse(
          session.getId(), session.getCampDate().format(YMD_FORMATTER),
          SchoolCampStatus.OPEN, null, null);
    }

    SchoolCampApplication application =
        applicationRepository.findBySessionIdAndCancelledAtIsNull(session.getId())
            .orElseThrow(() -> new IllegalStateException(
                "점유된 세션에 활성 신청이 없습니다: sessionId=" + session.getId()));

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
      sessionClaimService.release(sessionId);
      throw e;
    }
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

    Map<Long, User> studentsById = findExistingStudents(applicantUserId, additionalMembers);
    validateNoDuplicateThisMonth(applicantUserId, studentsById.keySet(), session.getCampDate());

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

  private void validateNoDuplicateThisMonth(
      Long applicantUserId, Set<Long> memberStudentIds, LocalDate campDate) {
    Set<Long> candidateIds = new HashSet<>(memberStudentIds);
    candidateIds.add(applicantUserId);

    YearMonth month = YearMonth.from(campDate);
    List<Long> participated = memberRepository.findParticipatedStudentIdsInMonth(
        candidateIds, month.atDay(1), month.atEndOfMonth());
    if (!participated.isEmpty()) {
      throw new CustomException(SchoolCampErrorCode.ALREADY_PARTICIPATED_THIS_MONTH);
    }
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
