package com.remake.gone.schoolcamp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.service.NotificationService;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.schoolcamp.dto.SchoolCampApplicationResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampApplyRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampMemberRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.entity.SchoolCampApplication;
import com.remake.gone.schoolcamp.entity.SchoolCampSession;
import com.remake.gone.schoolcamp.enums.SchoolCampStatus;
import com.remake.gone.schoolcamp.exception.SchoolCampErrorCode;
import com.remake.gone.schoolcamp.repository.SchoolCampApplicationRepository;
import com.remake.gone.schoolcamp.repository.SchoolCampMemberRepository;
import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
          schoolCampService.getCalendar(YearMonth.of(2026, 4));

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
          schoolCampService.getCalendar(YearMonth.of(2026, 4));

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
          schoolCampService.getCalendar(YearMonth.of(2026, 4));

      assertThat(result).containsExactly(new SchoolCampCalendarResponse(
          2L, "20260410", SchoolCampStatus.CLOSED, "김선생", "3218정문경"));
    }

    @Test
    @DisplayName("점유됐지만 활성 신청이 없는 세션(유령 점유)은 예외 없이 이름만 비운 CLOSED로 반환한다")
    void returnsClosedSessionWithNullNamesWhenApplicationMissing() {
      SchoolCampSession ghostSession = SchoolCampSession.builder()
          .id(3L)
          .campDate(LocalDate.of(2026, 4, 13))
          .takenAt(LocalDateTime.of(2026, 3, 20, 9, 12))
          .build();
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of(ghostSession));
      given(applicationRepository.findBySessionIdInAndCancelledAtIsNull(List.of(3L)))
          .willReturn(List.of());

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 4));

      assertThat(result).containsExactly(new SchoolCampCalendarResponse(
          3L, "20260413", SchoolCampStatus.CLOSED, null, null));
    }

    @Test
    @DisplayName("해당 달에 등록된 세션이 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoSessions() {
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)))
          .willReturn(List.of());

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 5));

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
      verify(sessionClaimService, never()).release(SESSION_ID);
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

      verify(sessionClaimService).release(SESSION_ID);
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

      verify(sessionClaimService).release(SESSION_ID);
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

      verify(sessionClaimService).release(SESSION_ID);
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

      verify(sessionClaimService).release(SESSION_ID);
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
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.ALREADY_PARTICIPATED_THIS_MONTH);

      verify(sessionClaimService).release(SESSION_ID);
      verifyNoInteractions(applicationRepository);
      verify(memberRepository, never()).saveAll(anyList());
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

      verify(sessionClaimService, never()).release(SESSION_ID);
      verify(notificationService, times(1))
          .send(eq(55L), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
      verify(notificationService, never())
          .send(eq(APPLICANT_ID), anyString(), anyString(), eq(NotificationType.SCHOOLCAMP));
    }
  }
}
