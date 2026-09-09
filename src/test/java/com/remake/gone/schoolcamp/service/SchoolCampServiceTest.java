package com.remake.gone.schoolcamp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link SchoolCampService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class SchoolCampServiceTest {

  @Mock
  private SchoolCampSessionRepository sessionRepository;

  @Mock
  private SchoolCampApplicationRepository applicationRepository;

  @Mock
  private SchoolCampMemberRepository memberRepository;

  @Mock
  private SchoolCampSessionClaimService sessionClaimService;

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserRoleRepository userRoleRepository;

  @Mock
  private NotificationService notificationService;

  @Mock
  private OutingRepository outingRepository;

  @Mock
  private SchoolCampWaitlistService waitlistService;

  @InjectMocks
  private SchoolCampService schoolCampService;

  private static Gbsw studentGbsw(int grade, int classNo, int number, String name) {
    return Gbsw.builder()
        .type(GbswType.STUDENT)
        .grade(grade)
        .classNo(classNo)
        .number(number)
        .name(name)
        .build();
  }

  private static User studentUser(Long id, Gbsw gbsw, String nickname) {
    return User.builder().id(id).gbsw(gbsw).name(nickname).build();
  }

  @Nested
  @DisplayName("registerCampDates")
  class RegisterCampDates {

    @Test
    @DisplayName("평일 날짜만 있으면 전부 등록하고 세션 목록을 반환한다")
    void registersAllWeekdayDates() {
      // 2026-04-03(금), 04(토), 05(일)은 제외하고 06(월)/07(화)만 사용
      given(sessionRepository.existsByCampDateIn(anyList())).willReturn(false);
      given(sessionRepository.saveAll(anyList())).willAnswer(invocation -> {
        List<SchoolCampSession> input = invocation.getArgument(0);
        long id = 1L;
        for (SchoolCampSession session : input) {
          session.setId(id++);
        }
        return input;
      });

      List<SchoolCampSessionResponse> result =
          schoolCampService.registerCampDates(List.of("20260406", "20260407"));

      assertThat(result).containsExactly(
          new SchoolCampSessionResponse(1L, "20260406"),
          new SchoolCampSessionResponse(2L, "20260407"));
    }

    @Test
    @DisplayName("금/토/일이 포함되면 전체를 거부한다")
    void rejectsWhenClosedDayOfWeekIncluded() {
      // 20260403은 금요일
      assertThatThrownBy(
          () -> schoolCampService.registerCampDates(List.of("20260406", "20260403")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_CAMP_DATE);

      verify(sessionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("이미 등록된 날짜가 포함되면 전체를 거부한다")
    void rejectsWhenDuplicateDateIncluded() {
      given(sessionRepository.existsByCampDateIn(anyList())).willReturn(true);

      assertThatThrownBy(
          () -> schoolCampService.registerCampDates(List.of("20260406", "20260407")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.CAMP_DATE_ALREADY_REGISTERED);

      verify(sessionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("요청 안에서 같은 날짜가 중복되면 DB 조회 없이 전체를 거부한다")
    void rejectsWhenRequestContainsSelfDuplicateDate() {
      assertThatThrownBy(
          () -> schoolCampService.registerCampDates(List.of("20260406", "20260406")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.CAMP_DATE_ALREADY_REGISTERED);

      verify(sessionRepository, never()).existsByCampDateIn(anyList());
      verify(sessionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("형식은 맞지만 실존하지 않는 날짜가 포함되면 전체를 거부한다")
    void rejectsWhenDateDoesNotExistOnCalendar() {
      // "20261332"는 8자리 숫자라 @Pattern은 통과하지만 13월은 실존하지 않는다
      assertThatThrownBy(
          () -> schoolCampService.registerCampDates(List.of("20261332")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_CAMP_DATE);

      verify(sessionRepository, never()).saveAll(anyList());
    }
  }

  @Nested
  @DisplayName("getCalendar")
  class GetCalendar {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 1, 0, 0);

    @Test
    @DisplayName("taken_at이 없는 세션은 OPEN이고 이름 필드가 null이다")
    void returnsOpenSessionWithNullNames() {
      SchoolCampSession openSession = SchoolCampSession.builder()
          .id(1L)
          .campDate(LocalDate.of(2026, 4, 6))
          .build();
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of(openSession));

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 4), NOW);

      assertThat(result).containsExactly(
          new SchoolCampCalendarResponse(1L, "20260406", SchoolCampStatus.OPEN, null, null));
    }

    @Test
    @DisplayName("taken_at이 있는 세션은 CLOSED이고 활성 신청에서 표시 이름을 채운다(자유 입력 선생님)")
    void returnsClosedSessionWithDisplayNames() {
      SchoolCampSession closedSession = SchoolCampSession.builder()
          .id(2L)
          .campDate(LocalDate.of(2026, 4, 10))
          .takenAt(LocalDateTime.of(2026, 3, 20, 9, 12))
          .build();
      User applicant = studentUser(101L, studentGbsw(3, 2, 18, "정문경"), "닉네임");
      SchoolCampApplication application = SchoolCampApplication.builder()
          .session(closedSession)
          .applicant(applicant)
          .teacherName("박선생")
          .build();
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of(closedSession));
      given(applicationRepository.findBySessionIdInAndCancelledAtIsNull(List.of(2L)))
          .willReturn(List.of(application));

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 4), NOW);

      assertThat(result).containsExactly(new SchoolCampCalendarResponse(
          2L, "20260410", SchoolCampStatus.CLOSED, "박선생", "3218정문경"));
    }

    @Test
    @DisplayName("taken_at이 있는 세션은 CLOSED이고 활성 신청에서 표시 이름을 채운다(가입된 선생님)")
    void returnsClosedSessionWithRegisteredTeacherDisplayName() {
      SchoolCampSession closedSession = SchoolCampSession.builder()
          .id(2L)
          .campDate(LocalDate.of(2026, 4, 10))
          .takenAt(LocalDateTime.of(2026, 3, 20, 9, 12))
          .build();
      User applicant = studentUser(101L, studentGbsw(3, 2, 18, "정문경"), "닉네임");
      User teacher = studentUser(42L, studentGbsw(1, 1, 1, "김선생"), "김선생");
      SchoolCampApplication application = SchoolCampApplication.builder()
          .session(closedSession)
          .applicant(applicant)
          .teacherUser(teacher)
          .build();
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of(closedSession));
      given(applicationRepository.findBySessionIdInAndCancelledAtIsNull(List.of(2L)))
          .willReturn(List.of(application));

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 4), NOW);

      assertThat(result).containsExactly(new SchoolCampCalendarResponse(
          2L, "20260410", SchoolCampStatus.CLOSED, "김선생", "3218정문경"));
    }

    @Test
    @DisplayName("점유됐지만 활성 신청이 없고 유예시간 내인 세션(유령 점유 의심)은 CLOSED로 방어적 반환한다")
    void returnsClosedSessionWithNullNamesWhenApplicationMissingWithinGracePeriod() {
      SchoolCampSession ghostSession = SchoolCampSession.builder()
          .id(3L)
          .campDate(LocalDate.of(2026, 4, 13))
          .takenAt(NOW.minusSeconds(30))
          .build();
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of(ghostSession));
      given(applicationRepository.findBySessionIdInAndCancelledAtIsNull(List.of(3L)))
          .willReturn(List.of());

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 4), NOW);

      assertThat(result).containsExactly(new SchoolCampCalendarResponse(
          3L, "20260413", SchoolCampStatus.CLOSED, null, null));
    }

    @Test
    @DisplayName("점유됐지만 활성 신청이 없고 유예시간이 지난 세션(유령 점유)은 OPEN으로 반환한다(#84)")
    void returnsOpenSessionWhenApplicationMissingAndGracePeriodExpired() {
      SchoolCampSession ghostSession = SchoolCampSession.builder()
          .id(3L)
          .campDate(LocalDate.of(2026, 4, 13))
          .takenAt(NOW.minus(SchoolCampSessionClaimService.GRACE_PERIOD).minusSeconds(1))
          .build();
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of(ghostSession));
      given(applicationRepository.findBySessionIdInAndCancelledAtIsNull(List.of(3L)))
          .willReturn(List.of());

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 4), NOW);

      assertThat(result).containsExactly(
          new SchoolCampCalendarResponse(3L, "20260413", SchoolCampStatus.OPEN, null, null));
    }

    @Test
    @DisplayName("해당 달에 등록된 세션이 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoSessions() {
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)))
          .willReturn(List.of());

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 5), NOW);

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("applyToCamp")
  class ApplyToCamp {

    private static final Long SESSION_ID = 5L;
    private static final Long APPLICANT_ID = 101L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 20, 9, 12);

    private SchoolCampSession session() {
      return SchoolCampSession.builder().id(SESSION_ID).campDate(LocalDate.of(2026, 4, 3)).build();
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 404를 던지고 점유를 시도하지 않는다")
    void throwsWhenSessionNotFound() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(42L, null, List.of());

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.SESSION_NOT_FOUND);

      verifyNoInteractions(sessionClaimService);
    }

    @Test
    @DisplayName("담당 선생님 정보가 둘 다 없거나 둘 다 있으면 400을 던지고 점유를 시도하지 않는다")
    void throwsWhenTeacherInfoInvalid() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(42L, "박선생", List.of());

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT);

      verifyNoInteractions(sessionClaimService);
    }

    @Test
    @DisplayName("총원(대표 포함)이 8명을 초과하면 400을 던지고 점유를 시도하지 않는다")
    void throwsWhenTeamSizeExceedsLimit() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      List<SchoolCampMemberRequest> eightAdditionalMembers = List.of(
          new SchoolCampMemberRequest(1L, null), new SchoolCampMemberRequest(2L, null),
          new SchoolCampMemberRequest(3L, null), new SchoolCampMemberRequest(4L, null),
          new SchoolCampMemberRequest(5L, null), new SchoolCampMemberRequest(6L, null),
          new SchoolCampMemberRequest(7L, null), new SchoolCampMemberRequest(8L, null));
      SchoolCampApplyRequest request =
          new SchoolCampApplyRequest(42L, null, eightAdditionalMembers);

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT);

      verifyNoInteractions(sessionClaimService);
    }

    @Test
    @DisplayName("이미 다른 신청이 세션을 선점했으면 409를 던지고 이후 무거운 검증은 수행하지 않는다")
    void throwsWhenSessionAlreadyTaken() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(false);
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(42L, null, List.of());

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.SESSION_ALREADY_TAKEN);

      verifyNoInteractions(
          userRepository, userRoleRepository, applicationRepository, memberRepository);
      verify(sessionClaimService, never()).release(SESSION_ID, NOW);
    }

    @Test
    @DisplayName("담당 선생님이 TEACHER 역할이 아니면 400을 던지고 세션 점유를 반환한다")
    void throwsAndReleasesWhenTeacherRoleInvalid() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(true);
      given(userRepository.findById(APPLICANT_ID))
          .willReturn(Optional.of(studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동")));
      given(userRepository.findById(42L))
          .willReturn(Optional.of(studentUser(42L, studentGbsw(1, 1, 1, "가짜쌤"), "가짜쌤")));
      given(userRoleRepository.findRoleCodesByUserId(42L)).willReturn(List.of("STUDENT"));
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(42L, null, List.of());

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT);

      verify(sessionClaimService).release(SESSION_ID, NOW);
      verifyNoInteractions(waitlistService);
      verifyNoInteractions(applicationRepository, memberRepository);
    }

    @Test
    @DisplayName("같은 studentUserId가 중복되면 400을 던지고 세션 점유를 반환한다")
    void throwsAndReleasesWhenMemberDuplicated() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(true);
      given(userRepository.findById(APPLICANT_ID))
          .willReturn(Optional.of(studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동")));
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of(
          new SchoolCampMemberRequest(55L, null), new SchoolCampMemberRequest(55L, null)));

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_MEMBER_INFO);

      verify(sessionClaimService).release(SESSION_ID, NOW);
      verifyNoInteractions(waitlistService);
      verifyNoInteractions(applicationRepository, memberRepository);
    }

    @Test
    @DisplayName("대표 신청자 본인이 팀원으로 포함되면 400을 던지고 세션 점유를 반환한다")
    void throwsAndReleasesWhenApplicantIncludedInMembers() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(true);
      given(userRepository.findById(APPLICANT_ID))
          .willReturn(Optional.of(studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동")));
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of(
          new SchoolCampMemberRequest(APPLICANT_ID, null)));

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_MEMBER_INFO);

      verify(sessionClaimService).release(SESSION_ID, NOW);
      verifyNoInteractions(waitlistService);
      verifyNoInteractions(applicationRepository, memberRepository);
    }

    @Test
    @DisplayName("존재하지 않는 studentUserId가 포함되면 400을 던지고 세션 점유를 반환한다")
    void throwsAndReleasesWhenMemberDoesNotExist() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(true);
      given(userRepository.findById(APPLICANT_ID))
          .willReturn(Optional.of(studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동")));
      given(userRepository.findAllById(List.of(999L))).willReturn(List.of());
      SchoolCampApplyRequest request =
          new SchoolCampApplyRequest(null, "박선생", List.of(new SchoolCampMemberRequest(999L, null)));

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_MEMBER_INFO);

      verify(sessionClaimService).release(SESSION_ID, NOW);
      verifyNoInteractions(waitlistService);
      verifyNoInteractions(applicationRepository, memberRepository);
    }

    @Test
    @DisplayName("이번 달에 이미 참여한 학생이 포함되면 409를 던지고 세션 점유를 반환한다")
    void throwsAndReleasesWhenAlreadyParticipatedThisMonth() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(true);
      given(userRepository.findById(APPLICANT_ID))
          .willReturn(Optional.of(studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동")));
      given(memberRepository.findParticipatedStudentIdsInMonth(
          anyCollection(), eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 30))))
          .willReturn(List.of(APPLICANT_ID));
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of());

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .satisfies(e -> {
            CustomException exception = (CustomException) e;
            assertThat(exception.getErrorCode())
                .isEqualTo(SchoolCampErrorCode.ALREADY_PARTICIPATED_THIS_MONTH);
            SchoolCampParticipationConflictResponse data =
                (SchoolCampParticipationConflictResponse) exception.getData();
            assertThat(data.conflictingMembers())
                .extracting(SchoolCampConflictingMemberResponse::studentUserId)
                .containsExactly(APPLICANT_ID);
            assertThat(data.conflictingMembers().get(0).studentRealName()).isEqualTo("홍길동");
          });

      verify(sessionClaimService).release(SESSION_ID, NOW);
      verifyNoInteractions(waitlistService);
      verifyNoInteractions(applicationRepository);
      verify(memberRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("대표 신청자 + 팀원이 동시에 이번 달 참여자면 둘 다 data에 담긴다(#81)")
    void includesAllConflictingMembersWhenMultipleParticipatedThisMonth() {
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session()));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(true);
      given(userRepository.findById(APPLICANT_ID))
          .willReturn(Optional.of(studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동")));
      User memberStudent = studentUser(55L, studentGbsw(3, 2, 9, "이영희"), "영희");
      given(userRepository.findAllById(List.of(55L))).willReturn(List.of(memberStudent));
      given(memberRepository.findParticipatedStudentIdsInMonth(
          anyCollection(), eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 30))))
          .willReturn(List.of(APPLICANT_ID, 55L));
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(
          null, "박선생", List.of(new SchoolCampMemberRequest(55L, null)));

      assertThatThrownBy(
          () -> schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW))
          .isInstanceOf(CustomException.class)
          .satisfies(e -> {
            SchoolCampParticipationConflictResponse data =
                (SchoolCampParticipationConflictResponse) ((CustomException) e).getData();
            assertThat(data.conflictingMembers())
                .extracting(SchoolCampConflictingMemberResponse::studentUserId)
                .containsExactlyInAnyOrder(APPLICANT_ID, 55L);
          });
    }

    @Test
    @DisplayName("정상 신청이면 세션을 점유하고 신청/팀원을 저장하며 가입된 팀원에게만 알림을 보낸다")
    void appliesSuccessfully() {
      SchoolCampSession session = session();
      given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
      given(sessionClaimService.claim(SESSION_ID, NOW)).willReturn(true);

      User applicant = studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동");
      given(userRepository.findById(APPLICANT_ID)).willReturn(Optional.of(applicant));

      User teacher = studentUser(42L, studentGbsw(1, 1, 1, "박선생"), "박선생");
      given(userRepository.findById(42L)).willReturn(Optional.of(teacher));
      given(userRoleRepository.findRoleCodesByUserId(42L)).willReturn(List.of("TEACHER"));

      User memberStudent = studentUser(55L, studentGbsw(3, 2, 9, "이영희"), "영희");
      given(userRepository.findAllById(List.of(55L))).willReturn(List.of(memberStudent));

      given(memberRepository.findParticipatedStudentIdsInMonth(
          anyCollection(), eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 30))))
          .willReturn(List.of());

      given(applicationRepository.save(any())).willAnswer(invocation -> {
        SchoolCampApplication application = invocation.getArgument(0);
        application.setId(301L);
        application.setAppliedAt(NOW);
        return application;
      });
      given(memberRepository.saveAll(anyList()))
          .willAnswer(invocation -> invocation.getArgument(0));

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(42L, null, List.of(
          new SchoolCampMemberRequest(55L, null),
          new SchoolCampMemberRequest(null, "김철수(옆반 아님, 외부인)")));

      SchoolCampApplicationResponse response =
          schoolCampService.applyToCamp(APPLICANT_ID, SESSION_ID, request, NOW);

      assertThat(response.id()).isEqualTo(301L);
      assertThat(response.campDate()).isEqualTo("20260403");
      assertThat(response.teacherDisplayName()).isEqualTo("박선생");
      assertThat(response.members()).hasSize(3);
      assertThat(response.members().get(0).isApplicant()).isTrue();
      assertThat(response.members().get(0).studentRealName()).isEqualTo("홍길동");
      assertThat(response.members().get(1).studentRealName()).isEqualTo("이영희");
      assertThat(response.members().get(2).guestName())
          .isEqualTo("김철수(옆반 아님, 외부인)");

      verify(sessionClaimService, never()).release(SESSION_ID, NOW);
      verify(notificationService, times(1))
          .send(eq(55L), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
      verify(notificationService, never())
          .send(eq(APPLICANT_ID), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
    }
  }

  @Nested
  @DisplayName("cancelApplication")
  class CancelApplication {

    private static final Long APPLICATION_ID = 301L;
    private static final Long APPLICANT_ID = 101L;
    private static final Long SESSION_ID = 15L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 10, 9, 0);
    private static final LocalDateTime SESSION_TAKEN_AT = LocalDateTime.of(2026, 3, 15, 10, 0);

    private SchoolCampApplication application(LocalDate campDate) {
      SchoolCampSession session = SchoolCampSession.builder()
          .id(SESSION_ID).campDate(campDate).takenAt(SESSION_TAKEN_AT).build();
      User applicant = studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동");
      return SchoolCampApplication.builder()
          .id(APPLICATION_ID)
          .session(session)
          .applicant(applicant)
          .teacherName("박선생")
          .build();
    }

    @Test
    @DisplayName("정상 취소 시 cancelledAt을 채우고 세션을 반환한다")
    void cancelsSuccessfully() {
      SchoolCampApplication application = application(LocalDate.of(2026, 4, 20));
      given(applicationRepository.findByIdAndCancelledAtIsNull(APPLICATION_ID))
          .willReturn(Optional.of(application));

      schoolCampService.cancelApplication(APPLICANT_ID, APPLICATION_ID, NOW);

      assertThat(application.getCancelledAt()).isEqualTo(NOW);
      verify(applicationRepository).save(application);
      verify(sessionRepository).release(SESSION_ID, SESSION_TAKEN_AT);
      verify(waitlistService)
          .notifyForMonth(YearMonth.from(application.getSession().getCampDate()));
      verifyNoInteractions(sessionClaimService);
    }

    @Test
    @DisplayName("본인 신청이 아니면 403을 던지고 아무것도 변경하지 않는다")
    void throwsWhenNotOwner() {
      SchoolCampApplication application = application(LocalDate.of(2026, 4, 20));
      given(applicationRepository.findByIdAndCancelledAtIsNull(APPLICATION_ID))
          .willReturn(Optional.of(application));

      assertThatThrownBy(() -> schoolCampService.cancelApplication(999L, APPLICATION_ID, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.NOT_APPLICATION_OWNER);

      verify(applicationRepository, never()).save(any());
      verify(sessionRepository, never()).release(anyLong(), any());
      verifyNoInteractions(waitlistService);
    }

    @Test
    @DisplayName("존재하지 않거나 이미 취소된 신청이면 404를 던진다")
    void throwsWhenApplicationNotFound() {
      given(applicationRepository.findByIdAndCancelledAtIsNull(APPLICATION_ID))
          .willReturn(Optional.empty());

      assertThatThrownBy(
          () -> schoolCampService.cancelApplication(APPLICANT_ID, APPLICATION_ID, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("캠핑 당일 취소 시도하면 400을 던지고 아무것도 변경하지 않는다")
    void throwsWhenCancelOnCampDay() {
      SchoolCampApplication application = application(NOW.toLocalDate());
      given(applicationRepository.findByIdAndCancelledAtIsNull(APPLICATION_ID))
          .willReturn(Optional.of(application));

      assertThatThrownBy(
          () -> schoolCampService.cancelApplication(APPLICANT_ID, APPLICATION_ID, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.CANCEL_NOT_ALLOWED_ON_CAMP_DAY);

      verify(applicationRepository, never()).save(any());
      verify(sessionRepository, never()).release(anyLong(), any());
      verifyNoInteractions(waitlistService);
    }
  }

  @Nested
  @DisplayName("updateApplication")
  class UpdateApplication {

    private static final Long APPLICATION_ID = 301L;
    private static final Long APPLICANT_ID = 101L;
    private static final Long SESSION_ID = 15L;

    private SchoolCampApplication application;
    private SchoolCampMember applicantMember;

    private void newApplication() {
      SchoolCampSession session = SchoolCampSession.builder()
          .id(SESSION_ID).campDate(LocalDate.of(2026, 4, 20)).build();
      User applicant = studentUser(APPLICANT_ID, studentGbsw(3, 4, 1, "홍길동"), "길동");
      application = SchoolCampApplication.builder()
          .id(APPLICATION_ID)
          .session(session)
          .applicant(applicant)
          .teacherName("박선생")
          .appliedAt(LocalDateTime.of(2026, 3, 20, 9, 12))
          .build();
      applicantMember = SchoolCampMember.builder()
          .id(1L).application(application).studentUser(applicant).applicant(true).build();
    }

    /** 소유권 확인까지만 도달하는 테스트용 — 신청 조회 스텁만 준비한다. */
    private void stubApplication() {
      if (application == null) {
        newApplication();
      }
      given(applicationRepository.findByIdAndCancelledAtIsNull(APPLICATION_ID))
          .willReturn(Optional.of(application));
    }

    /** diff 계산까지 도달하는 테스트용 — 신청 조회 + 기존 팀원 목록 스텁을 준비한다. */
    private void stubApplicationWithMembers(SchoolCampMember... otherMembers) {
      stubApplication();
      List<SchoolCampMember> members = new ArrayList<>(List.of(applicantMember));
      members.addAll(List.of(otherMembers));
      given(memberRepository.findByApplicationId(APPLICATION_ID)).willReturn(members);
    }

    @Test
    @DisplayName("담당 선생님만 변경하면 기존 팀원은 그대로 유지되고 세션은 건드리지 않는다")
    void changesTeacherOnlyKeepsExistingMembers() {
      newApplication();
      User existingStudent = studentUser(55L, studentGbsw(3, 2, 9, "이영희"), "영희");
      SchoolCampMember existingMember = SchoolCampMember.builder()
          .id(2L).application(application).studentUser(existingStudent).applicant(false).build();
      stubApplicationWithMembers(existingMember);
      given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

      User newTeacher = studentUser(99L, studentGbsw(1, 1, 1, "김선생"), "김선생");
      given(userRepository.findById(99L)).willReturn(Optional.of(newTeacher));
      given(userRoleRepository.findRoleCodesByUserId(99L)).willReturn(List.of("TEACHER"));
      given(userRepository.findAllById(List.of(55L))).willReturn(List.of(existingStudent));

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(
          99L, null, List.of(new SchoolCampMemberRequest(55L, null)));

      SchoolCampApplicationResponse response =
          schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request);

      assertThat(response.teacherDisplayName()).isEqualTo("김선생");
      assertThat(response.members()).hasSize(2);
      verify(memberRepository, never()).deleteAllById(anyList());
      verify(memberRepository, never()).saveAll(anyList());
      verify(memberRepository, never()).findParticipatedStudentIdsInMonth(any(), any(), any());
      verifyNoInteractions(sessionRepository, sessionClaimService);
    }

    @Test
    @DisplayName("가입 학생 + 기타 팀원을 새로 추가하면 추가된 팀원에게만 알림을 보낸다")
    void addsRegisteredAndGuestMembers() {
      stubApplicationWithMembers();
      given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
      given(memberRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

      User newStudent = studentUser(77L, studentGbsw(2, 1, 3, "최민수"), "민수");
      given(userRepository.findAllById(List.of(77L))).willReturn(List.of(newStudent));
      given(memberRepository.findParticipatedStudentIdsInMonth(anyCollection(), any(), any()))
          .willReturn(List.of());

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of(
          new SchoolCampMemberRequest(77L, null),
          new SchoolCampMemberRequest(null, "새게스트")));

      SchoolCampApplicationResponse response =
          schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request);

      assertThat(response.members()).hasSize(3);
      verify(memberRepository).saveAll(anyList());
      verify(notificationService)
          .send(eq(77L), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
      verify(notificationService, never())
          .send(eq(APPLICANT_ID), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
    }

    @Test
    @DisplayName("요청에서 빠진 기존 팀원은 삭제된다")
    void removesMemberNotInRequest() {
      newApplication();
      User existingStudent = studentUser(55L, studentGbsw(3, 2, 9, "이영희"), "영희");
      SchoolCampMember existingMember = SchoolCampMember.builder()
          .id(2L).application(application).studentUser(existingStudent).applicant(false).build();
      stubApplicationWithMembers(existingMember);
      given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of());

      SchoolCampApplicationResponse response =
          schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request);

      assertThat(response.members()).hasSize(1);
      verify(memberRepository).deleteAllById(List.of(2L));
    }

    @Test
    @DisplayName("한 요청 안에서 유지/추가/제거가 섞여도 diff가 올바르게 적용된다")
    void keepsAddsAndRemovesMembersInSameRequest() {
      newApplication();
      User keptStudent = studentUser(55L, studentGbsw(3, 2, 9, "이영희"), "영희");
      User removedStudent = studentUser(66L, studentGbsw(2, 3, 4, "박서준"), "서준");
      SchoolCampMember keptMember = SchoolCampMember.builder()
          .id(2L).application(application).studentUser(keptStudent).applicant(false).build();
      SchoolCampMember removedMember = SchoolCampMember.builder()
          .id(3L).application(application).studentUser(removedStudent).applicant(false).build();
      stubApplicationWithMembers(keptMember, removedMember);
      given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
      given(memberRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

      User newStudent = studentUser(77L, studentGbsw(2, 1, 3, "최민수"), "민수");
      given(userRepository.findAllById(List.of(55L, 77L)))
          .willReturn(List.of(keptStudent, newStudent));
      given(memberRepository.findParticipatedStudentIdsInMonth(anyCollection(), any(), any()))
          .willReturn(List.of());

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of(
          new SchoolCampMemberRequest(55L, null),
          new SchoolCampMemberRequest(77L, null),
          new SchoolCampMemberRequest(null, "새게스트")));

      SchoolCampApplicationResponse response =
          schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request);

      assertThat(response.members()).hasSize(4);
      assertThat(response.members())
          .extracting(m -> m.studentRealName() != null ? m.studentRealName() : m.guestName())
          .containsExactlyInAnyOrder("홍길동", "이영희", "최민수", "새게스트");
      verify(memberRepository).deleteAllById(List.of(3L));
      verify(memberRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("추가하는 팀원이 전부 기타(게스트)면 이번 달 중복 참여 확인을 건너뛴다")
    void addsOnlyGuestMembersSkipsMonthlyDuplicateCheck() {
      stubApplicationWithMembers();
      given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
      given(memberRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of(
          new SchoolCampMemberRequest(null, "게스트1"),
          new SchoolCampMemberRequest(null, "게스트2")));

      SchoolCampApplicationResponse response =
          schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request);

      assertThat(response.members()).hasSize(3);
      verify(memberRepository, never()).findParticipatedStudentIdsInMonth(any(), any(), any());
    }

    @Test
    @DisplayName("팀원 저장 중 동시 수정 충돌(유니크 제약 위반)이 발생하면 409로 변환한다")
    void throwsConflictWhenConcurrentInsertViolatesUniqueConstraint() {
      stubApplicationWithMembers();

      User newStudent = studentUser(77L, studentGbsw(2, 1, 3, "최민수"), "민수");
      given(userRepository.findAllById(List.of(77L))).willReturn(List.of(newStudent));
      given(memberRepository.findParticipatedStudentIdsInMonth(anyCollection(), any(), any()))
          .willReturn(List.of());
      given(memberRepository.saveAll(anyList()))
          .willThrow(new DataIntegrityViolationException("duplicate"));

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(
          null, "박선생", List.of(new SchoolCampMemberRequest(77L, null)));

      assertThatThrownBy(
          () -> schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.CONCURRENT_UPDATE_CONFLICT);

      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("새로 추가한 팀원이 이번 달 이미 참여 중이면 409를 던지고 아무것도 변경하지 않는다")
    void throwsWhenAddedMemberAlreadyParticipatedThisMonth() {
      stubApplicationWithMembers();

      User newStudent = studentUser(88L, studentGbsw(2, 1, 4, "박지민"), "지민");
      given(userRepository.findAllById(List.of(88L))).willReturn(List.of(newStudent));
      given(memberRepository.findParticipatedStudentIdsInMonth(anyCollection(), any(), any()))
          .willReturn(List.of(88L));

      SchoolCampApplyRequest request = new SchoolCampApplyRequest(
          null, "박선생", List.of(new SchoolCampMemberRequest(88L, null)));

      assertThatThrownBy(
          () -> schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request))
          .isInstanceOf(CustomException.class)
          .satisfies(e -> {
            CustomException exception = (CustomException) e;
            assertThat(exception.getErrorCode())
                .isEqualTo(SchoolCampErrorCode.ALREADY_PARTICIPATED_THIS_MONTH);
            SchoolCampParticipationConflictResponse data =
                (SchoolCampParticipationConflictResponse) exception.getData();
            assertThat(data.conflictingMembers())
                .extracting(SchoolCampConflictingMemberResponse::studentUserId)
                .containsExactly(88L);
            assertThat(data.conflictingMembers().get(0).studentRealName()).isEqualTo("박지민");
          });

      verify(memberRepository, never()).deleteAllById(anyList());
      verify(memberRepository, never()).saveAll(anyList());
      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("본인 신청이 아니면 403을 던진다")
    void throwsWhenNotOwner() {
      stubApplication();
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of());

      assertThatThrownBy(() -> schoolCampService.updateApplication(999L, APPLICATION_ID, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.NOT_APPLICATION_OWNER);
    }

    @Test
    @DisplayName("존재하지 않거나 취소된 신청이면 404를 던진다")
    void throwsWhenApplicationNotFound() {
      given(applicationRepository.findByIdAndCancelledAtIsNull(APPLICATION_ID))
          .willReturn(Optional.empty());
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(null, "박선생", List.of());

      assertThatThrownBy(
          () -> schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("총원이 8명을 초과하면 400을 던진다")
    void throwsWhenTeamSizeExceedsLimit() {
      stubApplication();
      List<SchoolCampMemberRequest> eightAdditionalMembers = List.of(
          new SchoolCampMemberRequest(1L, null), new SchoolCampMemberRequest(2L, null),
          new SchoolCampMemberRequest(3L, null), new SchoolCampMemberRequest(4L, null),
          new SchoolCampMemberRequest(5L, null), new SchoolCampMemberRequest(6L, null),
          new SchoolCampMemberRequest(7L, null), new SchoolCampMemberRequest(8L, null));
      SchoolCampApplyRequest request =
          new SchoolCampApplyRequest(null, "박선생", eightAdditionalMembers);

      assertThatThrownBy(
          () -> schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_APPLICATION_FORMAT);
    }

    @Test
    @DisplayName("존재하지 않는 studentUserId가 포함되면 400을 던진다")
    void throwsWhenMemberDoesNotExist() {
      stubApplication();
      given(userRepository.findAllById(List.of(999L))).willReturn(List.of());
      SchoolCampApplyRequest request = new SchoolCampApplyRequest(
          null, "박선생", List.of(new SchoolCampMemberRequest(999L, null)));

      assertThatThrownBy(
          () -> schoolCampService.updateApplication(APPLICANT_ID, APPLICATION_ID, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_MEMBER_INFO);
    }
  }

  @Nested
  @DisplayName("sendOutingReminders")
  class SendOutingReminders {

    private static final Long APPLICATION_ID = 301L;
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 20);

    private SchoolCampApplication application() {
      SchoolCampSession session =
          SchoolCampSession.builder().id(15L).campDate(TODAY).build();
      User applicant = studentUser(101L, studentGbsw(3, 4, 1, "홍길동"), "길동");
      return SchoolCampApplication.builder()
          .id(APPLICATION_ID).session(session).applicant(applicant).teacherName("박선생").build();
    }

    private SchoolCampMember memberOf(Long studentId, String name) {
      User student = studentUser(studentId, studentGbsw(3, 2, 9, name), name);
      return SchoolCampMember.builder().studentUser(student).applicant(false).build();
    }

    private Outing outing(LocalTime startTime, LocalTime endTime) {
      return Outing.builder().startTime(startTime).endTime(endTime).build();
    }

    /** {@code studentId}의 오늘 활성 외출증 목록을 스텁한다. */
    private void stubOutings(Long studentId, List<Outing> outings) {
      given(outingRepository.findByStudentIdAndOutingDateAndStatusIn(
          eq(studentId), eq(TODAY), eq(OutingStatus.ACTIVE_STATUSES)))
          .willReturn(outings);
    }

    @Test
    @DisplayName("오늘 활성 신청이 없으면 아무 알림도 발송하지 않는다")
    void sendsNothingWhenNoApplicationToday() {
      given(applicationRepository.findBySessionCampDateAndCancelledAtIsNull(TODAY))
          .willReturn(List.of());

      schoolCampService.sendOutingReminders(TODAY);

      verifyNoInteractions(memberRepository, outingRepository, notificationService);
    }

    @Test
    @DisplayName("LUNCH 외출증을 이미 신청한 학생에게는 알림을 보내지 않는다")
    void skipsMemberWithExistingLunchOuting() {
      given(applicationRepository.findBySessionCampDateAndCancelledAtIsNull(TODAY))
          .willReturn(List.of(application()));
      given(memberRepository.findByApplicationId(APPLICATION_ID))
          .willReturn(List.of(memberOf(55L, "이영희")));
      stubOutings(55L, List.of(outing(OutingTimeSlot.LUNCH.getStartTime(),
          OutingTimeSlot.LUNCH.getEndTime())));

      schoolCampService.sendOutingReminders(TODAY);

      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("점심시간을 포함하는 CUSTOM 외출증이 있으면 알림을 보내지 않는다(코드 리뷰 #71 대응)")
    void skipsMemberWithCustomOutingOverlappingLunch() {
      // timeSlot 이름이 아니라 실제 시작/종료 시각으로 겹침을 판단해야 하는 케이스 —
      // 11:00~15:00 CUSTOM 외출증은 LUNCH(12:30~13:40)를 완전히 포함한다.
      given(applicationRepository.findBySessionCampDateAndCancelledAtIsNull(TODAY))
          .willReturn(List.of(application()));
      given(memberRepository.findByApplicationId(APPLICATION_ID))
          .willReturn(List.of(memberOf(55L, "이영희")));
      stubOutings(55L, List.of(outing(LocalTime.of(11, 0), LocalTime.of(15, 0))));

      schoolCampService.sendOutingReminders(TODAY);

      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("점심시간을 포함하지 않는 CUSTOM 외출증만 있으면 여전히 리마인더 대상이다")
    void remindsMemberWithCustomOutingNotOverlappingLunch() {
      given(applicationRepository.findBySessionCampDateAndCancelledAtIsNull(TODAY))
          .willReturn(List.of(application()));
      given(memberRepository.findByApplicationId(APPLICATION_ID))
          .willReturn(List.of(memberOf(55L, "이영희")));
      stubOutings(55L, List.of(outing(LocalTime.of(8, 40), LocalTime.of(10, 0))));

      schoolCampService.sendOutingReminders(TODAY);

      verify(notificationService)
          .send(eq(55L), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
    }

    @Test
    @DisplayName("외출증이 없는 학생에게는 리마인더 알림을 보낸다")
    void remindsMemberWithoutAnyOuting() {
      given(applicationRepository.findBySessionCampDateAndCancelledAtIsNull(TODAY))
          .willReturn(List.of(application()));
      given(memberRepository.findByApplicationId(APPLICATION_ID))
          .willReturn(List.of(memberOf(55L, "이영희")));
      stubOutings(55L, List.of());

      schoolCampService.sendOutingReminders(TODAY);

      verify(notificationService)
          .send(eq(55L), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
    }

    @Test
    @DisplayName("계정이 없는 기타(자유 입력) 팀원은 알림 대상에서 제외한다")
    void excludesGuestMemberWithoutAccount() {
      given(applicationRepository.findBySessionCampDateAndCancelledAtIsNull(TODAY))
          .willReturn(List.of(application()));
      SchoolCampMember guest = SchoolCampMember.builder()
          .guestName("김철수(옆반 아님, 외부인)").applicant(false).build();
      given(memberRepository.findByApplicationId(APPLICATION_ID)).willReturn(List.of(guest));

      schoolCampService.sendOutingReminders(TODAY);

      verifyNoInteractions(outingRepository, notificationService);
    }
  }

  @Nested
  @DisplayName("getMyParticipations")
  class GetMyParticipations {

    private static final Long MY_ID = 101L;

    private User me() {
      return studentUser(MY_ID, studentGbsw(3, 4, 1, "홍길동"), "길동");
    }

    private SchoolCampApplication application(
        Long applicationId, LocalDate campDate, User applicant, LocalDateTime cancelledAt) {
      SchoolCampSession session =
          SchoolCampSession.builder().id(50L).campDate(campDate).build();
      return SchoolCampApplication.builder()
          .id(applicationId).session(session).applicant(applicant).teacherName("박선생")
          .appliedAt(LocalDateTime.of(2026, 3, 20, 9, 12)).cancelledAt(cancelledAt).build();
    }

    private SchoolCampMember myRow(SchoolCampApplication application, boolean isApplicant) {
      return SchoolCampMember.builder()
          .application(application).studentUser(me()).applicant(isApplicant).build();
    }

    /** 본인이 담당 선생님으로 지정된 신청(설계 변경 3, 선생님 이력). */
    private SchoolCampApplication teacherApplication(
        Long applicationId, LocalDate campDate, LocalDateTime cancelledAt) {
      SchoolCampSession session =
          SchoolCampSession.builder().id(51L).campDate(campDate).build();
      User someApplicant = studentUser(202L, studentGbsw(2, 1, 3, "최민수"), "민수");
      return SchoolCampApplication.builder()
          .id(applicationId).session(session).applicant(someApplicant).teacherUser(me())
          .appliedAt(LocalDateTime.of(2026, 3, 20, 9, 12)).cancelledAt(cancelledAt).build();
    }

    /** month=null 경로에서 선생님 쪽 이력이 없다고 스텁한다(학생 전용 테스트용). */
    private void stubNoTeacherHistory() {
      given(applicationRepository.findByTeacherUserId(MY_ID)).willReturn(List.of());
    }

    @Test
    @DisplayName("month를 생략하면 findMyParticipations/findByTeacherUserId로 전체 이력을 조회한다")
    void queriesAllHistoryWhenMonthOmitted() {
      given(memberRepository.findMyParticipations(MY_ID)).willReturn(List.of());
      stubNoTeacherHistory();

      PageResponse<SchoolCampMyParticipationSummaryResponse> result =
          schoolCampService.getMyParticipations(MY_ID, null, 0, 20);

      assertThat(result.content()).isEmpty();
      verify(memberRepository, never())
          .findMyParticipationsInMonth(any(), any(), any());
      verify(applicationRepository, never())
          .findByTeacherUserIdInMonth(any(), any(), any());
    }

    @Test
    @DisplayName("month를 지정하면 findMyParticipationsInMonth/findByTeacherUserIdInMonth로 그 달만 조회한다")
    void queriesSingleMonthWhenMonthGiven() {
      YearMonth month = YearMonth.of(2026, 4);
      given(memberRepository.findMyParticipationsInMonth(
          MY_ID, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of());
      given(applicationRepository.findByTeacherUserIdInMonth(
          MY_ID, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of());

      schoolCampService.getMyParticipations(MY_ID, month, 0, 20);

      verify(memberRepository, never()).findMyParticipations(any());
      verify(applicationRepository, never()).findByTeacherUserId(any());
    }

    @Test
    @DisplayName("대표로 참여한 신청은 myRole이 APPLICANT다")
    void applicantRoleForOwnApplication() {
      SchoolCampApplication application =
          application(301L, LocalDate.of(2026, 4, 3), me(), null);
      given(memberRepository.findMyParticipations(MY_ID))
          .willReturn(List.of(myRow(application, true)));
      stubNoTeacherHistory();

      PageResponse<SchoolCampMyParticipationSummaryResponse> result =
          schoolCampService.getMyParticipations(MY_ID, null, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).myRole()).isEqualTo(SchoolCampMyRole.APPLICANT);
      assertThat(result.content().get(0).id()).isEqualTo(301L);
      assertThat(result.content().get(0).teacherDisplayName()).isEqualTo("박선생");
    }

    @Test
    @DisplayName("팀원으로 참여한 신청은 myRole이 MEMBER다")
    void memberRoleForInvitedApplication() {
      User otherApplicant = studentUser(202L, studentGbsw(2, 1, 3, "최민수"), "민수");
      SchoolCampApplication application =
          application(305L, LocalDate.of(2026, 4, 17), otherApplicant, null);
      given(memberRepository.findMyParticipations(MY_ID))
          .willReturn(List.of(myRow(application, false)));
      stubNoTeacherHistory();

      PageResponse<SchoolCampMyParticipationSummaryResponse> result =
          schoolCampService.getMyParticipations(MY_ID, null, 0, 20);

      assertThat(result.content().get(0).myRole()).isEqualTo(SchoolCampMyRole.MEMBER);
    }

    @Test
    @DisplayName("담당 선생님으로 지정된 신청은 myRole이 TEACHER다(설계 변경 3)")
    void teacherRoleForOwnAssignedApplication() {
      SchoolCampApplication application =
          teacherApplication(401L, LocalDate.of(2026, 4, 10), null);
      given(memberRepository.findMyParticipations(MY_ID)).willReturn(List.of());
      given(applicationRepository.findByTeacherUserId(MY_ID)).willReturn(List.of(application));

      PageResponse<SchoolCampMyParticipationSummaryResponse> result =
          schoolCampService.getMyParticipations(MY_ID, null, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).myRole()).isEqualTo(SchoolCampMyRole.TEACHER);
      assertThat(result.content().get(0).id()).isEqualTo(401L);
    }

    @Test
    @DisplayName("학생 이력과 선생님 이력이 campDate 내림차순으로 병합된다(설계 변경 3)")
    void mergesStudentAndTeacherHistoriesSortedByCampDate() {
      SchoolCampApplication studentApplication =
          application(1L, LocalDate.of(2026, 3, 1), me(), null);
      SchoolCampApplication teachingApplication =
          teacherApplication(2L, LocalDate.of(2026, 5, 1), null);
      given(memberRepository.findMyParticipations(MY_ID))
          .willReturn(List.of(myRow(studentApplication, true)));
      given(applicationRepository.findByTeacherUserId(MY_ID))
          .willReturn(List.of(teachingApplication));

      PageResponse<SchoolCampMyParticipationSummaryResponse> result =
          schoolCampService.getMyParticipations(MY_ID, null, 0, 20);

      assertThat(result.content())
          .extracting(SchoolCampMyParticipationSummaryResponse::id)
          .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("취소된 신청도 목록에 포함되고 cancelledAt이 채워진다")
    void includesCancelledApplicationWithCancelledAt() {
      LocalDateTime cancelledAt = LocalDateTime.of(2026, 2, 25, 10, 0);
      SchoolCampApplication application =
          application(288L, LocalDate.of(2026, 3, 6), me(), cancelledAt);
      given(memberRepository.findMyParticipations(MY_ID))
          .willReturn(List.of(myRow(application, true)));
      stubNoTeacherHistory();

      PageResponse<SchoolCampMyParticipationSummaryResponse> result =
          schoolCampService.getMyParticipations(MY_ID, null, 0, 20);

      assertThat(result.content().get(0).cancelledAt())
          .isEqualTo(cancelledAt.toString());
    }

    @Test
    @DisplayName("페이지네이션이 적용된다")
    void appliesPagination() {
      List<SchoolCampMember> rows = List.of(
          myRow(application(1L, LocalDate.of(2026, 1, 1), me(), null), true),
          myRow(application(2L, LocalDate.of(2026, 2, 1), me(), null), true),
          myRow(application(3L, LocalDate.of(2026, 3, 1), me(), null), true));
      given(memberRepository.findMyParticipations(MY_ID)).willReturn(rows);
      stubNoTeacherHistory();

      PageResponse<SchoolCampMyParticipationSummaryResponse> result =
          schoolCampService.getMyParticipations(MY_ID, null, 0, 2);

      assertThat(result.content()).hasSize(2);
      assertThat(result.totalElements()).isEqualTo(3);
      assertThat(result.hasNext()).isTrue();
    }

    @Test
    @DisplayName("page가 음수면 400을 던진다")
    void throwsWhenPageNegative() {
      assertThatThrownBy(() -> schoolCampService.getMyParticipations(MY_ID, null, -1, 20))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_PAGE_PARAMS);
    }

    @Test
    @DisplayName("size가 범위 밖이면 400을 던진다")
    void throwsWhenSizeOutOfRange() {
      assertThatThrownBy(() -> schoolCampService.getMyParticipations(MY_ID, null, 0, 101))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.INVALID_PAGE_PARAMS);
    }
  }

  @Nested
  @DisplayName("getMyParticipationDetail")
  class GetMyParticipationDetail {

    private static final Long MY_ID = 101L;
    private static final Long APPLICATION_ID = 301L;

    private User me() {
      return studentUser(MY_ID, studentGbsw(3, 4, 1, "홍길동"), "길동");
    }

    private SchoolCampApplication application(LocalDateTime cancelledAt) {
      SchoolCampSession session =
          SchoolCampSession.builder().id(50L).campDate(LocalDate.of(2026, 4, 3)).build();
      return SchoolCampApplication.builder()
          .id(APPLICATION_ID).session(session).applicant(me()).teacherName("박선생")
          .appliedAt(LocalDateTime.of(2026, 3, 20, 9, 12)).cancelledAt(cancelledAt).build();
    }

    @Test
    @DisplayName("대표로 참여한 신청 상세를 조회하면 myRole이 APPLICANT이고 팀원 전체를 포함한다")
    void returnsDetailForApplicant() {
      SchoolCampApplication application = application(null);
      SchoolCampMember myMember = SchoolCampMember.builder()
          .application(application).studentUser(me()).applicant(true).build();
      User otherStudent = studentUser(55L, studentGbsw(3, 2, 9, "이영희"), "영희");
      SchoolCampMember otherMember = SchoolCampMember.builder()
          .application(application).studentUser(otherStudent).applicant(false).build();
      given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(application));
      given(memberRepository.findByApplicationId(APPLICATION_ID))
          .willReturn(List.of(myMember, otherMember));

      SchoolCampMyParticipationResponse response =
          schoolCampService.getMyParticipationDetail(MY_ID, APPLICATION_ID);

      assertThat(response.myRole()).isEqualTo(SchoolCampMyRole.APPLICANT);
      assertThat(response.members()).hasSize(2);
    }

    @Test
    @DisplayName("담당 선생님이 상세를 조회하면 myRole이 TEACHER다(설계 변경 3)")
    void returnsDetailForTeacher() {
      SchoolCampSession session =
          SchoolCampSession.builder().id(50L).campDate(LocalDate.of(2026, 4, 3)).build();
      User applicant = studentUser(202L, studentGbsw(2, 1, 3, "최민수"), "민수");
      SchoolCampApplication application = SchoolCampApplication.builder()
          .id(APPLICATION_ID).session(session).applicant(applicant).teacherUser(me())
          .appliedAt(LocalDateTime.of(2026, 3, 20, 9, 12)).build();
      SchoolCampMember applicantMember = SchoolCampMember.builder()
          .application(application).studentUser(applicant).applicant(true).build();
      given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(application));
      given(memberRepository.findByApplicationId(APPLICATION_ID))
          .willReturn(List.of(applicantMember));

      SchoolCampMyParticipationResponse response =
          schoolCampService.getMyParticipationDetail(MY_ID, APPLICATION_ID);

      assertThat(response.myRole()).isEqualTo(SchoolCampMyRole.TEACHER);
    }

    @Test
    @DisplayName("취소된 신청도 상세 조회가 된다")
    void returnsDetailForCancelledApplication() {
      LocalDateTime cancelledAt = LocalDateTime.of(2026, 3, 25, 10, 0);
      SchoolCampApplication application = application(cancelledAt);
      SchoolCampMember myMember = SchoolCampMember.builder()
          .application(application).studentUser(me()).applicant(true).build();
      given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(application));
      given(memberRepository.findByApplicationId(APPLICATION_ID)).willReturn(List.of(myMember));

      SchoolCampMyParticipationResponse response =
          schoolCampService.getMyParticipationDetail(MY_ID, APPLICATION_ID);

      assertThat(response.cancelledAt()).isEqualTo(cancelledAt.toString());
    }

    @Test
    @DisplayName("존재하지 않는 신청이면 404를 던진다")
    void throwsWhenApplicationNotFound() {
      given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.empty());

      assertThatThrownBy(
          () -> schoolCampService.getMyParticipationDetail(MY_ID, APPLICATION_ID))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("본인이 참여자가 아니면 403을 던진다")
    void throwsWhenNotParticipant() {
      SchoolCampApplication application = application(null);
      User otherStudent = studentUser(55L, studentGbsw(3, 2, 9, "이영희"), "영희");
      SchoolCampMember otherMember = SchoolCampMember.builder()
          .application(application).studentUser(otherStudent).applicant(true).build();
      given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(application));
      given(memberRepository.findByApplicationId(APPLICATION_ID))
          .willReturn(List.of(otherMember));

      assertThatThrownBy(
          () -> schoolCampService.getMyParticipationDetail(MY_ID, APPLICATION_ID))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.NOT_APPLICATION_PARTICIPANT);
    }
  }
}
