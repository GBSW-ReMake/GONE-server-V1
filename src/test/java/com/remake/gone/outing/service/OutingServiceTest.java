package com.remake.gone.outing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.outing.config.OutingProperties;
import com.remake.gone.outing.dto.OutingActiveResponse;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingLocationRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingQueryPeriod;
import com.remake.gone.outing.enums.OutingQueryStatus;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.exception.OutingErrorCode;
import com.remake.gone.outing.repository.OutingRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * {@link OutingService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class OutingServiceTest {

  private static final Long STUDENT_ID = 1L;
  private static final Long TEACHER_ID = 42L;

  // 2026-08-10(월)~2026-08-14(금) — 마스터 기획서 예시와 동일한 "이번 주" 기준.
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);
  private static final LocalTime NOW = LocalTime.of(9, 0);
  private static final String OUTING_DATE = "20260814";

  @Mock
  private OutingRepository outingRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserRoleRepository userRoleRepository;

  @Mock
  private R2FileService r2FileService;

  @Mock
  private OutingProperties outingProperties;

  @InjectMocks
  private OutingService outingService;

  private User student() {
    Gbsw gbsw = Gbsw.builder()
        .type(GbswType.STUDENT)
        .name("홍길동")
        .phoneNumber("01011112222")
        .grade(3)
        .classNo(4)
        .number(12)
        .build();
    return User.builder()
        .id(STUDENT_ID)
        .gbsw(gbsw)
        .loginId("student1")
        .passwordHash("hash")
        .name("길동이")
        .phoneNumber("01011112222")
        .build();
  }

  private User studentWithoutClass() {
    Gbsw gbsw = Gbsw.builder()
        .type(GbswType.STUDENT)
        .name("홍길동")
        .phoneNumber("01011112222")
        .build();
    return User.builder()
        .id(STUDENT_ID)
        .gbsw(gbsw)
        .loginId("student1")
        .passwordHash("hash")
        .name("길동이")
        .phoneNumber("01011112222")
        .build();
  }

  private User teacher() {
    Gbsw gbsw = Gbsw.builder()
        .type(GbswType.TEACHER)
        .name("김선생")
        .phoneNumber("01033334444")
        .build();
    return User.builder()
        .id(TEACHER_ID)
        .gbsw(gbsw)
        .loginId("teacher1")
        .passwordHash("hash")
        .name("쌤")
        .phoneNumber("01033334444")
        .build();
  }

  private OutingApplyRequest request(OutingTimeSlot timeSlot, String start, String end) {
    return new OutingApplyRequest("치과 진료", OUTING_DATE, timeSlot, start, end, TEACHER_ID);
  }

  private void givenStudentAndTeacherRolesOk() {
    given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));
    given(userRoleRepository.findRoleCodesByUserId(TEACHER_ID)).willReturn(List.of("TEACHER"));
  }

  @Nested
  @DisplayName("applyOuting")
  class ApplyOuting {

    @Test
    @DisplayName("프리셋(LUNCH)으로 정상 신청하면 PENDING 상태로 생성된다")
    void appliesSuccessfullyWithPresetTimeSlot() {
      givenStudentAndTeacherRolesOk();
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.of(teacher()));
      given(userRepository.findByIdForUpdate(STUDENT_ID)).willReturn(Optional.of(student()));
      given(outingRepository.findByStudentIdAndOutingDateAndStatusIn(
          STUDENT_ID, LocalDate.parse("2026-08-14"), OutingStatus.ACTIVE_STATUSES))
          .willReturn(List.of());
      given(outingRepository.save(any(Outing.class))).willAnswer(invocation -> invocation
          .getArgument(0, Outing.class).toBuilder().id(501L).build());

      OutingResponse response = outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW);

      assertThat(response.status()).isEqualTo(OutingStatus.PENDING);
      assertThat(response.timeSlot()).isEqualTo(OutingTimeSlot.LUNCH);
      assertThat(response.startTime()).isEqualTo("12:30");
      assertThat(response.endTime()).isEqualTo("13:40");
      assertThat(response.studentNickname()).isEqualTo("길동이");
      assertThat(response.studentRealName()).isEqualTo("홍길동");
      assertThat(response.studentGrade()).isEqualTo(3);
      assertThat(response.studentClassNo()).isEqualTo(4);
      assertThat(response.teacherName()).isEqualTo("김선생");
      assertThat(response.code()).hasSize(10);
    }

    @Test
    @DisplayName("커스텀 시간대로 정상 신청하면 요청한 시각 그대로 저장된다")
    void appliesSuccessfullyWithCustomTimeSlot() {
      givenStudentAndTeacherRolesOk();
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.of(teacher()));
      given(userRepository.findByIdForUpdate(STUDENT_ID)).willReturn(Optional.of(student()));
      given(outingRepository.findByStudentIdAndOutingDateAndStatusIn(
          STUDENT_ID, LocalDate.parse("2026-08-14"), OutingStatus.ACTIVE_STATUSES))
          .willReturn(List.of());
      given(outingRepository.save(any(Outing.class))).willAnswer(invocation -> invocation
          .getArgument(0, Outing.class).toBuilder().id(502L).build());

      OutingResponse response = outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.CUSTOM, "14:00", "16:00"), TODAY, NOW);

      assertThat(response.timeSlot()).isEqualTo(OutingTimeSlot.CUSTOM);
      assertThat(response.startTime()).isEqualTo("14:00");
      assertThat(response.endTime()).isEqualTo("16:00");
    }

    @Test
    @DisplayName("STUDENT 역할이 없으면 거부한다")
    void rejectsWhenCallerIsNotStudent() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.STUDENT_ROLE_REQUIRED);
    }

    @Test
    @DisplayName("outingDate가 지난 날짜면 거부한다")
    void rejectsWhenDateIsInThePast() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));
      OutingApplyRequest pastRequest =
          new OutingApplyRequest("치과 진료", "20260809", OutingTimeSlot.LUNCH, null, null, TEACHER_ID);

      assertThatThrownBy(() -> outingService.applyOuting(STUDENT_ID, pastRequest, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_DATE_OR_TIME);
    }

    @Test
    @DisplayName("outingDate가 이번 주 금요일을 넘으면 거부한다")
    void rejectsWhenDateIsBeyondThisFriday() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));
      OutingApplyRequest nextWeekRequest =
          new OutingApplyRequest("치과 진료", "20260817", OutingTimeSlot.LUNCH, null, null, TEACHER_ID);

      assertThatThrownBy(() -> outingService.applyOuting(STUDENT_ID, nextWeekRequest, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_DATE_OR_TIME);
    }

    @Test
    @DisplayName("오늘 날짜인데 이미 그 시간대 시작 시각이 지났으면 거부한다")
    void rejectsWhenDeadlineHasPassed() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));
      OutingApplyRequest todayRequest =
          new OutingApplyRequest("치과 진료", "20260810", OutingTimeSlot.LUNCH, null, null, TEACHER_ID);

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, todayRequest, TODAY, LocalTime.of(13, 0)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_DATE_OR_TIME);
    }

    @Test
    @DisplayName("커스텀 시작 시각이 08:40 이전이면 거부한다")
    void rejectsWhenCustomStartTimeBeforeWindow() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.CUSTOM, "08:00", "10:00"), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_CUSTOM_TIME_RANGE);
    }

    @Test
    @DisplayName("커스텀 종료 시각이 20:30을 넘으면 거부한다")
    void rejectsWhenCustomEndTimeAfterWindow() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.CUSTOM, "19:00", "21:00"), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_CUSTOM_TIME_RANGE);
    }

    @Test
    @DisplayName("커스텀 종료 시각이 시작 시각보다 빠르면 거부한다")
    void rejectsWhenCustomEndTimeBeforeStartTime() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.CUSTOM, "16:00", "14:00"), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_CUSTOM_TIME_RANGE);
    }

    @Test
    @DisplayName("지정한 teacherUserId가 존재하지 않으면 거부한다")
    void rejectsWhenTeacherNotFound() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.TEACHER_NOT_FOUND);
    }

    @Test
    @DisplayName("지정한 사용자가 TEACHER 역할이 아니면 거부한다")
    void rejectsWhenTeacherRoleMissing() {
      given(userRoleRepository.findRoleCodesByUserId(STUDENT_ID)).willReturn(List.of("STUDENT"));
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.of(teacher()));
      given(userRoleRepository.findRoleCodesByUserId(TEACHER_ID)).willReturn(List.of("STUDENT"));

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.TEACHER_NOT_FOUND);
    }

    @Test
    @DisplayName("학급 정보(학년/반)가 없는 학생 계정이면 거부한다")
    void rejectsWhenStudentHasNoClassAssigned() {
      givenStudentAndTeacherRolesOk();
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.of(teacher()));
      given(userRepository.findByIdForUpdate(STUDENT_ID))
          .willReturn(Optional.of(studentWithoutClass()));

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(GbswErrorCode.NO_CLASS_ASSIGNED);
    }

    @Test
    @DisplayName("같은 날짜에 시간이 겹치는 활성 외출증이 있으면 거부한다")
    void rejectsWhenTimeOverlapsWithActiveOuting() {
      givenStudentAndTeacherRolesOk();
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.of(teacher()));
      given(userRepository.findByIdForUpdate(STUDENT_ID)).willReturn(Optional.of(student()));
      Outing existing = Outing.builder()
          .id(500L)
          .code("EXISTINGCODE1")
          .student(student())
          .teacher(teacher())
          .reason("기존 외출")
          .outingDate(LocalDate.parse("2026-08-14"))
          .timeSlot(OutingTimeSlot.CUSTOM)
          .startTime(LocalTime.of(13, 0))
          .endTime(LocalTime.of(14, 30))
          .status(OutingStatus.PENDING)
          .build();
      given(outingRepository.findByStudentIdAndOutingDateAndStatusIn(
          STUDENT_ID, LocalDate.parse("2026-08-14"), OutingStatus.ACTIVE_STATUSES))
          .willReturn(List.of(existing));

      // 기존 13:00~14:30과 겹치는 LUNCH(12:30~13:40) 신청
      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.TIME_OVERLAP);
    }

    @Test
    @DisplayName("code가 중복되면 재생성해서 재시도한 뒤 저장에 성공한다")
    void retriesCodeGenerationOnCollision() {
      givenStudentAndTeacherRolesOk();
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.of(teacher()));
      given(userRepository.findByIdForUpdate(STUDENT_ID)).willReturn(Optional.of(student()));
      given(outingRepository.findByStudentIdAndOutingDateAndStatusIn(
          STUDENT_ID, LocalDate.parse("2026-08-14"), OutingStatus.ACTIVE_STATUSES))
          .willReturn(List.of());
      given(outingRepository.save(any(Outing.class)))
          .willThrow(new DataIntegrityViolationException("code 중복"))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class)
              .toBuilder().id(503L).build());

      OutingResponse response = outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW);

      assertThat(response.status()).isEqualTo(OutingStatus.PENDING);
      verify(outingRepository, times(2)).save(any(Outing.class));
    }

    @Test
    @DisplayName("code 생성 재시도를 모두 소진하면 원본 예외를 그대로 던진다")
    void throwsOriginalExceptionWhenCodeGenerationExhaustsRetries() {
      givenStudentAndTeacherRolesOk();
      given(userRepository.findById(TEACHER_ID)).willReturn(Optional.of(teacher()));
      given(userRepository.findByIdForUpdate(STUDENT_ID)).willReturn(Optional.of(student()));
      given(outingRepository.findByStudentIdAndOutingDateAndStatusIn(
          STUDENT_ID, LocalDate.parse("2026-08-14"), OutingStatus.ACTIVE_STATUSES))
          .willReturn(List.of());
      DataIntegrityViolationException persistentFailure =
          new DataIntegrityViolationException("code 중복");
      given(outingRepository.save(any(Outing.class))).willThrow(persistentFailure);

      assertThatThrownBy(() -> outingService.applyOuting(
          STUDENT_ID, request(OutingTimeSlot.LUNCH, null, null), TODAY, NOW))
          .isSameAs(persistentFailure);
      verify(outingRepository, times(5)).save(any(Outing.class));
    }
  }

  @Nested
  @DisplayName("approveOuting")
  class ApproveOuting {

    private static final String OUTING_CODE = "8A1zx9202n";
    private static final LocalDateTime NOW_DATETIME = LocalDateTime.of(2026, 8, 10, 9, 0);

    private Outing pendingOuting() {
      return Outing.builder()
          .id(500L)
          .code(OUTING_CODE)
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(LocalDate.parse("2026-08-14"))
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(LocalTime.of(12, 30))
          .endTime(LocalTime.of(13, 40))
          .status(OutingStatus.PENDING)
          .build();
    }

    @Test
    @DisplayName("담당 선생님이 PENDING 외출증을 승인하면 APPROVED로 바뀐다")
    void approvesSuccessfully() {
      Outing outing = pendingOuting();
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.save(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));

      OutingResponse response =
          outingService.approveOuting(TEACHER_ID, OUTING_CODE, NOW_DATETIME);

      assertThat(response.status()).isEqualTo(OutingStatus.APPROVED);
      assertThat(outing.getApprovedAt()).isEqualTo(NOW_DATETIME);
      verify(outingRepository).save(outing);
    }

    @Test
    @DisplayName("존재하지 않는 code면 거부한다")
    void rejectsWhenOutingNotFound() {
      given(outingRepository.findByCode("NOPE")).willReturn(Optional.empty());

      assertThatThrownBy(() -> outingService.approveOuting(TEACHER_ID, "NOPE", NOW_DATETIME))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTING_NOT_FOUND);
    }

    @Test
    @DisplayName("본인에게 지정된 담당 선생님이 아니면 거부한다")
    void rejectsWhenTeacherMismatch() {
      Outing outing = pendingOuting();
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      Long otherTeacherId = 99L;

      assertThatThrownBy(() -> outingService.approveOuting(
          otherTeacherId, OUTING_CODE, NOW_DATETIME))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.TEACHER_MISMATCH);
    }

    @Test
    @DisplayName("이미 PENDING이 아닌 외출증이면 거부한다")
    void rejectsWhenAlreadyProcessed() {
      Outing outing = pendingOuting();
      outing.setStatus(OutingStatus.APPROVED);
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));

      assertThatThrownBy(() -> outingService.approveOuting(TEACHER_ID, OUTING_CODE, NOW_DATETIME))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("이미 시작 시각이 지난 PENDING 외출증이면 거부한다(#42)")
    void rejectsWhenDeadlineHasPassed() {
      Outing outing = pendingOuting();
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      LocalDateTime pastDeadline = LocalDateTime.of(2026, 8, 14, 13, 0);

      assertThatThrownBy(() -> outingService.approveOuting(TEACHER_ID, OUTING_CODE, pastDeadline))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.DEADLINE_PASSED);
    }
  }

  @Nested
  @DisplayName("rejectOuting")
  class RejectOuting {

    private static final String OUTING_CODE = "8A1zx9202n";
    private static final String REJECTED_REASON = "지금은 상담 시간이라 곤란해요";
    private static final LocalDateTime NOW_DATETIME = LocalDateTime.of(2026, 8, 10, 9, 0);

    private Outing pendingOuting() {
      return Outing.builder()
          .id(500L)
          .code(OUTING_CODE)
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(LocalDate.parse("2026-08-14"))
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(LocalTime.of(12, 30))
          .endTime(LocalTime.of(13, 40))
          .status(OutingStatus.PENDING)
          .build();
    }

    @Test
    @DisplayName("담당 선생님이 PENDING 외출증을 거절하면 REJECTED로 바뀌고 사유가 저장된다")
    void rejectsSuccessfully() {
      Outing outing = pendingOuting();
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.save(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));

      OutingResponse response =
          outingService.rejectOuting(TEACHER_ID, OUTING_CODE, REJECTED_REASON, NOW_DATETIME);

      assertThat(response.status()).isEqualTo(OutingStatus.REJECTED);
      assertThat(response.rejectedReason()).isEqualTo(REJECTED_REASON);
      assertThat(outing.getRejectedReason()).isEqualTo(REJECTED_REASON);
      verify(outingRepository).save(outing);
    }

    @Test
    @DisplayName("존재하지 않는 code면 거부한다")
    void rejectsWhenOutingNotFound() {
      given(outingRepository.findByCode("NOPE")).willReturn(Optional.empty());

      assertThatThrownBy(() -> outingService.rejectOuting(
          TEACHER_ID, "NOPE", REJECTED_REASON, NOW_DATETIME))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTING_NOT_FOUND);
    }

    @Test
    @DisplayName("본인에게 지정된 담당 선생님이 아니면 거부한다")
    void rejectsWhenTeacherMismatch() {
      Outing outing = pendingOuting();
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      Long otherTeacherId = 99L;

      assertThatThrownBy(() -> outingService.rejectOuting(
          otherTeacherId, OUTING_CODE, REJECTED_REASON, NOW_DATETIME))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.TEACHER_MISMATCH);
    }

    @Test
    @DisplayName("이미 PENDING이 아닌 외출증이면 거부한다")
    void rejectsWhenAlreadyProcessed() {
      Outing outing = pendingOuting();
      outing.setStatus(OutingStatus.APPROVED);
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));

      assertThatThrownBy(() -> outingService.rejectOuting(
          TEACHER_ID, OUTING_CODE, REJECTED_REASON, NOW_DATETIME))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("이미 시작 시각이 지난 PENDING 외출증이면 거부한다(#42)")
    void rejectsWhenDeadlineHasPassed() {
      Outing outing = pendingOuting();
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      LocalDateTime pastDeadline = LocalDateTime.of(2026, 8, 14, 13, 0);

      assertThatThrownBy(() -> outingService.rejectOuting(
          TEACHER_ID, OUTING_CODE, REJECTED_REASON, pastDeadline))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.DEADLINE_PASSED);
    }
  }

  @Nested
  @DisplayName("departOuting")
  class DepartOuting {

    private static final String OUTING_CODE = "8A1zx9202n";
    private static final double SCHOOL_LATITUDE = 36.0;
    private static final double SCHOOL_LONGITUDE = 128.0;

    private Outing approvedOuting(LocalDate outingDate, LocalTime start, LocalTime end) {
      return Outing.builder()
          .id(500L)
          .code(OUTING_CODE)
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(outingDate)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(start)
          .endTime(end)
          .status(OutingStatus.APPROVED)
          .build();
    }

    private void givenSchoolPropertiesOk() {
      given(outingProperties.schoolLatitude()).willReturn(SCHOOL_LATITUDE);
      given(outingProperties.schoolLongitude()).willReturn(SCHOOL_LONGITUDE);
      given(outingProperties.schoolRadiusMeters()).willReturn(200);
    }

    @Test
    @DisplayName("APPROVED 외출증을 학교 반경 안에서 출발 보고하면 DEPARTED로 바뀌고 좌표가 저장된다")
    void departsSuccessfully() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));
      givenSchoolPropertiesOk();
      LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 31);

      OutingResponse response = outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          now);

      assertThat(response.status()).isEqualTo(OutingStatus.DEPARTED);
      assertThat(response.departedAt()).isEqualTo(now);
      assertThat(response.offSchedule()).isFalse();
      assertThat(outing.getDepartedLatitude()).isEqualTo(SCHOOL_LATITUDE);
      assertThat(outing.getDepartedLongitude()).isEqualTo(SCHOOL_LONGITUDE);
      verify(outingRepository).saveAndFlush(outing);
    }

    @Test
    @DisplayName("존재하지 않는 code면 거부한다")
    void rejectsWhenOutingNotFound() {
      given(outingRepository.findByCode("NOPE")).willReturn(Optional.empty());

      assertThatThrownBy(() -> outingService.departOuting(
          STUDENT_ID, "NOPE", new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 12, 31)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTING_NOT_FOUND);
    }

    @Test
    @DisplayName("본인 외출증이 아니면 거부한다")
    void rejectsWhenNotOwner() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      Long otherStudentId = 99L;

      assertThatThrownBy(() -> outingService.departOuting(
          otherStudentId, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 12, 31)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("운영시간(08:40~20:30) 밖에서 호출하면 거부한다(08:39)")
    void rejectsWhenBeforeOperatingHours() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));

      assertThatThrownBy(() -> outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 8, 39)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTSIDE_OPERATING_HOURS);
    }

    @Test
    @DisplayName("운영시간 하한(08:40) 정각은 허용한다(경계값 포함)")
    void allowsExactlyAtOperatingHoursStart() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(8, 40), LocalTime.of(9, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));
      givenSchoolPropertiesOk();

      OutingResponse response = outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 8, 40));

      assertThat(response.status()).isEqualTo(OutingStatus.DEPARTED);
    }

    @Test
    @DisplayName("운영시간 상한(20:30) 정각은 허용한다(경계값 포함)")
    void allowsExactlyAtOperatingHoursEnd() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(19, 30), LocalTime.of(20, 30));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));
      givenSchoolPropertiesOk();

      OutingResponse response = outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 20, 30));

      assertThat(response.status()).isEqualTo(OutingStatus.DEPARTED);
    }

    @Test
    @DisplayName("운영시간(08:40~20:30) 밖에서 호출하면 거부한다(20:31)")
    void rejectsWhenAfterOperatingHours() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));

      assertThatThrownBy(() -> outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 20, 31)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTSIDE_OPERATING_HOURS);
    }

    @Test
    @DisplayName("APPROVED 상태가 아니면 거부한다")
    void rejectsWhenNotApproved() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      outing.setStatus(OutingStatus.PENDING);
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));

      assertThatThrownBy(() -> outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 12, 31)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("학교 반경 밖에서 시도하면 거부한다")
    void rejectsWhenOutOfSchoolRadius() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      givenSchoolPropertiesOk();

      // 학교(36.0, 128.0)에서 위도 1도(약 111km) 떨어진 지점 — 반경 200m를 훨씬 벗어난다.
      assertThatThrownBy(() -> outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(37.0, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 12, 31)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUT_OF_SCHOOL_RADIUS);
    }

    @Test
    @DisplayName("운영시간 안이지만 이 외출증의 예정 시간대 밖에서 출발하면 차단되지 않고 offSchedule만 true다")
    void allowsButFlagsOffScheduleWhenOutsideOwnTimeSlot() {
      // LUNCH(12:30~13:40)인데 저녁 시간대(18:00)에 출발 보고.
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));
      givenSchoolPropertiesOk();

      OutingResponse response = outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 18, 0));

      assertThat(response.status()).isEqualTo(OutingStatus.DEPARTED);
      assertThat(response.offSchedule()).isTrue();
    }

    @Test
    @DisplayName("저장 중 낙관적 락 충돌이 나면 500이 아니라 409(ALREADY_PROCESSED)로 변환한다"
        + "(#43 코드 리뷰 Medium 2번 대응)")
    void convertsOptimisticLockFailureToAlreadyProcessed() {
      Outing outing = approvedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willThrow(new ObjectOptimisticLockingFailureException(Outing.class, OUTING_CODE));
      givenSchoolPropertiesOk();

      assertThatThrownBy(() -> outingService.departOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 12, 31)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ALREADY_PROCESSED);
    }
  }

  @Nested
  @DisplayName("returnOuting")
  class ReturnOuting {

    private static final String OUTING_CODE = "8A1zx9202n";
    private static final double SCHOOL_LATITUDE = 36.0;
    private static final double SCHOOL_LONGITUDE = 128.0;

    private Outing departedOuting(LocalDate outingDate, LocalTime start, LocalTime end) {
      return Outing.builder()
          .id(500L)
          .code(OUTING_CODE)
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(outingDate)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(start)
          .endTime(end)
          .status(OutingStatus.DEPARTED)
          .departedAt(outingDate.atTime(start).plusMinutes(1))
          .build();
    }

    private void givenSchoolPropertiesOk() {
      given(outingProperties.schoolLatitude()).willReturn(SCHOOL_LATITUDE);
      given(outingProperties.schoolLongitude()).willReturn(SCHOOL_LONGITUDE);
      given(outingProperties.schoolRadiusMeters()).willReturn(200);
    }

    @Test
    @DisplayName("DEPARTED 외출증을 학교 반경 안에서 도착 보고하면 RETURNED로 바뀌고 좌표가 저장된다")
    void returnsSuccessfully() {
      Outing outing = departedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));
      givenSchoolPropertiesOk();
      LocalDateTime now = LocalDateTime.of(2026, 8, 10, 13, 30);

      OutingResponse response = outingService.returnOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          now);

      assertThat(response.status()).isEqualTo(OutingStatus.RETURNED);
      assertThat(response.returnedAt()).isEqualTo(now);
      assertThat(response.offSchedule()).isFalse();
      assertThat(outing.getReturnedLatitude()).isEqualTo(SCHOOL_LATITUDE);
      assertThat(outing.getReturnedLongitude()).isEqualTo(SCHOOL_LONGITUDE);
      verify(outingRepository).saveAndFlush(outing);
    }

    @Test
    @DisplayName("존재하지 않는 code면 거부한다")
    void rejectsWhenOutingNotFound() {
      given(outingRepository.findByCode("NOPE")).willReturn(Optional.empty());

      assertThatThrownBy(() -> outingService.returnOuting(
          STUDENT_ID, "NOPE", new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 13, 30)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTING_NOT_FOUND);
    }

    @Test
    @DisplayName("본인 외출증이 아니면 거부한다")
    void rejectsWhenNotOwner() {
      Outing outing = departedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      Long otherStudentId = 99L;

      assertThatThrownBy(() -> outingService.returnOuting(
          otherStudentId, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 13, 30)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("운영시간(08:40~20:30) 밖에서 호출하면 거부한다")
    void rejectsWhenOutsideOperatingHours() {
      Outing outing = departedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));

      assertThatThrownBy(() -> outingService.returnOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 20, 31)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTSIDE_OPERATING_HOURS);
    }

    @Test
    @DisplayName("DEPARTED 상태가 아니면 거부한다")
    void rejectsWhenNotDeparted() {
      Outing outing = departedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      outing.setStatus(OutingStatus.APPROVED);
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));

      assertThatThrownBy(() -> outingService.returnOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 13, 30)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("학교 반경 밖에서 시도하면 거부한다")
    void rejectsWhenOutOfSchoolRadius() {
      Outing outing = departedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      givenSchoolPropertiesOk();

      assertThatThrownBy(() -> outingService.returnOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(37.0, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 13, 30)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUT_OF_SCHOOL_RADIUS);
    }

    @Test
    @DisplayName("운영시간 안이지만 이 외출증의 예정 시간대 이전에 도착하면 차단되지 않고 offSchedule만 true다")
    void allowsButFlagsOffScheduleWhenBeforeOwnTimeSlot() {
      Outing outing = departedOuting(TODAY, LocalTime.of(18, 10), LocalTime.of(19, 10));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willAnswer(invocation -> invocation.getArgument(0, Outing.class));
      givenSchoolPropertiesOk();

      // DINNER(18:10~19:10)인데 운영시간 안인 09:00에 도착 보고.
      OutingResponse response = outingService.returnOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 9, 0));

      assertThat(response.status()).isEqualTo(OutingStatus.RETURNED);
      assertThat(response.offSchedule()).isTrue();
    }

    @Test
    @DisplayName("저장 중 낙관적 락 충돌이 나면 500이 아니라 409(ALREADY_PROCESSED)로 변환한다"
        + "(#43 코드 리뷰 Medium 2번 대응)")
    void convertsOptimisticLockFailureToAlreadyProcessed() {
      Outing outing = departedOuting(TODAY, LocalTime.of(12, 30), LocalTime.of(13, 40));
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing));
      given(outingRepository.saveAndFlush(any(Outing.class)))
          .willThrow(new ObjectOptimisticLockingFailureException(Outing.class, OUTING_CODE));
      givenSchoolPropertiesOk();

      assertThatThrownBy(() -> outingService.returnOuting(
          STUDENT_ID, OUTING_CODE, new OutingLocationRequest(SCHOOL_LATITUDE, SCHOOL_LONGITUDE),
          LocalDateTime.of(2026, 8, 10, 13, 30)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ALREADY_PROCESSED);
    }
  }

  @Nested
  @DisplayName("findOverdueOutingIds")
  class FindOverdueOutingIds {

    private Outing pendingOuting(Long id, String code, LocalDate outingDate, LocalTime startTime) {
      return Outing.builder()
          .id(id)
          .code(code)
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(outingDate)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(startTime)
          .endTime(startTime.plusHours(1))
          .status(OutingStatus.PENDING)
          .build();
    }

    @Test
    @DisplayName("마감이 지난 PENDING 외출증의 ID만 반환하고, 아직 안 지난 건은 뺀다")
    void returnsOnlyOverdueOutingIds() {
      Outing overdue = pendingOuting(700L, "OVERDUE001", TODAY, LocalTime.of(8, 0));
      Outing notYetDue = pendingOuting(
          701L, "NOTDUE0001", LocalDate.of(2026, 8, 14), LocalTime.of(12, 30));
      given(outingRepository.findByStatus(OutingStatus.PENDING))
          .willReturn(List.of(overdue, notYetDue));

      List<Long> overdueIds = outingService.findOverdueOutingIds(TODAY, NOW);

      assertThat(overdueIds).containsExactly(700L);
    }

    @Test
    @DisplayName("마감 지난 PENDING 건이 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoneOverdue() {
      given(outingRepository.findByStatus(OutingStatus.PENDING)).willReturn(List.of());

      List<Long> overdueIds = outingService.findOverdueOutingIds(TODAY, NOW);

      assertThat(overdueIds).isEmpty();
    }
  }

  @Nested
  @DisplayName("markSingleOutingAsMissed")
  class MarkSingleOutingAsMissed {

    private static final Long OUTING_ID = 700L;

    private Outing pendingOuting() {
      return Outing.builder()
          .id(OUTING_ID)
          .code("OVERDUE001")
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(TODAY)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(LocalTime.of(8, 0))
          .endTime(LocalTime.of(9, 0))
          .status(OutingStatus.PENDING)
          .build();
    }

    @Test
    @DisplayName("PENDING 외출증이면 MISSED로 갱신한다")
    void marksPendingOutingAsMissed() {
      Outing outing = pendingOuting();
      given(outingRepository.findById(OUTING_ID)).willReturn(Optional.of(outing));

      outingService.markSingleOutingAsMissed(OUTING_ID);

      assertThat(outing.getStatus()).isEqualTo(OutingStatus.MISSED);
      verify(outingRepository).save(outing);
    }

    @Test
    @DisplayName("이미 PENDING이 아니면(승인/거절이 먼저 커밋됨) 건드리지 않는다")
    void skipsWhenAlreadyProcessed() {
      Outing outing = pendingOuting();
      outing.setStatus(OutingStatus.APPROVED);
      given(outingRepository.findById(OUTING_ID)).willReturn(Optional.of(outing));

      outingService.markSingleOutingAsMissed(OUTING_ID);

      assertThat(outing.getStatus()).isEqualTo(OutingStatus.APPROVED);
      verify(outingRepository, times(0)).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 ID면 아무것도 하지 않는다")
    void skipsWhenNotFound() {
      given(outingRepository.findById(OUTING_ID)).willReturn(Optional.empty());

      outingService.markSingleOutingAsMissed(OUTING_ID);

      verify(outingRepository, times(0)).save(any());
    }

    @Test
    @DisplayName("저장 중 낙관적 락 충돌이 나면 예외를 삼키고 조용히 넘어간다")
    void swallowsOptimisticLockingFailure() {
      Outing outing = pendingOuting();
      given(outingRepository.findById(OUTING_ID)).willReturn(Optional.of(outing));
      given(outingRepository.save(outing))
          .willThrow(new ObjectOptimisticLockingFailureException(Outing.class, OUTING_ID));

      outingService.markSingleOutingAsMissed(OUTING_ID);

      verify(outingRepository).save(outing);
    }
  }

  @Nested
  @DisplayName("getMyRequests")
  class GetMyRequests {

    private Outing outingWithStatus(OutingStatus status, LocalDate outingDate, LocalTime start) {
      return Outing.builder()
          .id(600L)
          .code("REQCODE001")
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(outingDate)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(start)
          .endTime(start.plusHours(1))
          .status(status)
          .build();
    }

    @Test
    @DisplayName("period=THIS_WEEK면 TODAY가 속한 주(월~일)로 조회한다")
    void resolvesThisWeekRangeFromToday() {
      // TODAY = 2026-08-10(월) → 이번 주는 2026-08-10(월)~2026-08-16(일)
      given(outingRepository.findStudentRequestsPage(
          eq(STUDENT_ID), eq(LocalDate.of(2026, 8, 10)), eq(LocalDate.of(2026, 8, 16)),
          isNull(), isNull(), eq(TODAY), eq(NOW), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

      PageResponse<OutingResponse> response = outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.THIS_WEEK, null, null, null, 0, 20, TODAY, NOW);

      assertThat(response.content()).isEmpty();
    }

    @Test
    @DisplayName("범위 안에 신청한 게 없으면 빈 배열을 반환한다(null 아님)")
    void returnsEmptyListNotNullWhenNoResults() {
      given(outingRepository.findStudentRequestsPage(
          eq(STUDENT_ID), eq(TODAY), eq(TODAY), isNull(), isNull(), eq(TODAY), eq(NOW),
          any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

      PageResponse<OutingResponse> response = outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, null, 0, 20, TODAY, NOW);

      assertThat(response.content()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("PENDING이고 마감이 지난 건은 MISSED로 표시한다(DB 값은 그대로 PENDING)")
    void showsMissedForExpiredPending() {
      Outing pastDeadline = outingWithStatus(OutingStatus.PENDING, TODAY, LocalTime.of(8, 0));
      given(outingRepository.findStudentRequestsPage(
          eq(STUDENT_ID), eq(TODAY), eq(TODAY), isNull(), isNull(), eq(TODAY), eq(NOW),
          any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(pastDeadline), PageRequest.of(0, 20), 1));

      PageResponse<OutingResponse> response = outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, null, 0, 20, TODAY, NOW);

      assertThat(response.content()).hasSize(1);
      assertThat(response.content().get(0).status()).isEqualTo(OutingStatus.MISSED);
      assertThat(pastDeadline.getStatus()).isEqualTo(OutingStatus.PENDING);
    }

    @Test
    @DisplayName("status=PENDING으로 필터링하면 statusEq=PENDING, wantExpired=false로 조회한다")
    void statusFilterPendingResolvesToNotExpired() {
      Outing stillPending = outingWithStatus(OutingStatus.PENDING, TODAY, LocalTime.of(18, 0));
      given(outingRepository.findStudentRequestsPage(
          eq(STUDENT_ID), eq(TODAY), eq(TODAY), eq(OutingStatus.PENDING), eq(false),
          eq(TODAY), eq(NOW), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(stillPending), PageRequest.of(0, 20), 1));

      PageResponse<OutingResponse> response = outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, OutingQueryStatus.PENDING, 0, 20, TODAY, NOW);

      assertThat(response.content()).hasSize(1);
      assertThat(response.content().get(0).status()).isEqualTo(OutingStatus.PENDING);
    }

    @Test
    @DisplayName("status=MISSED로 필터링하면 statusEq=PENDING, wantExpired=true로 조회한다")
    void statusFilterMissedResolvesToExpired() {
      Outing pastDeadline = outingWithStatus(OutingStatus.PENDING, TODAY, LocalTime.of(8, 0));
      given(outingRepository.findStudentRequestsPage(
          eq(STUDENT_ID), eq(TODAY), eq(TODAY), eq(OutingStatus.PENDING), eq(true),
          eq(TODAY), eq(NOW), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(pastDeadline), PageRequest.of(0, 20), 1));

      PageResponse<OutingResponse> response = outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, OutingQueryStatus.MISSED, 0, 20, TODAY, NOW);

      assertThat(response.content()).hasSize(1);
      assertThat(response.content().get(0).status()).isEqualTo(OutingStatus.MISSED);
    }

    @Test
    @DisplayName("status=DEPARTED로 필터링하면 statusEq=DEPARTED, wantExpired=null로 조회한다")
    void statusFilterDepartedResolvesToDirectMatch() {
      Outing departed = outingWithStatus(OutingStatus.DEPARTED, TODAY, LocalTime.of(8, 0));
      given(outingRepository.findStudentRequestsPage(
          eq(STUDENT_ID), eq(TODAY), eq(TODAY), eq(OutingStatus.DEPARTED), isNull(),
          eq(TODAY), eq(NOW), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(departed), PageRequest.of(0, 20), 1));

      PageResponse<OutingResponse> response = outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, OutingQueryStatus.DEPARTED, 0, 20, TODAY, NOW);

      assertThat(response.content()).hasSize(1);
      assertThat(response.content().get(0).status()).isEqualTo(OutingStatus.DEPARTED);
    }

    @Test
    @DisplayName("period=CUSTOM인데 dateFrom/dateTo가 없으면 거부한다")
    void rejectsWhenCustomMissingDates() {
      assertThatThrownBy(() -> outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.CUSTOM,
          null, null, null, 0, 20, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_PERIOD_PARAMS);
    }

    @Test
    @DisplayName("period=THIS_WEEK인데 dateFrom이 같이 오면 거부한다")
    void rejectsWhenNonCustomHasDateParams() {
      assertThatThrownBy(() -> outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.THIS_WEEK,
          TODAY, null, null, 0, 20, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_PERIOD_PARAMS);
    }

    @Test
    @DisplayName("period=CUSTOM에서 dateFrom이 dateTo보다 늦으면 거부한다")
    void rejectsWhenDateFromAfterDateTo() {
      assertThatThrownBy(() -> outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.CUSTOM,
          LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 10), null, 0, 20, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("page가 음수면 거부한다")
    void rejectsWhenPageNegative() {
      assertThatThrownBy(() -> outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, null, -1, 20, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_PAGE_PARAMS);
    }

    @Test
    @DisplayName("size가 100을 초과하면 거부한다")
    void rejectsWhenSizeTooLarge() {
      assertThatThrownBy(() -> outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, null, 0, 101, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_PAGE_PARAMS);
    }

    @Test
    @DisplayName("size가 0이면 거부한다")
    void rejectsWhenSizeZero() {
      assertThatThrownBy(() -> outingService.getMyRequests(
          STUDENT_ID, OutingQueryPeriod.TODAY,
          null, null, null, 0, 0, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_PAGE_PARAMS);
    }
  }

  @Nested
  @DisplayName("getReceivedOutings")
  class GetReceivedOutings {

    @Test
    @DisplayName("담당 선생님으로 지정된 외출증을 조회한다")
    void returnsOutingsAssignedToTeacher() {
      Outing pending = Outing.builder()
          .id(700L)
          .code("RECVCODE01")
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(TODAY)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(LocalTime.of(12, 30))
          .endTime(LocalTime.of(13, 40))
          .status(OutingStatus.PENDING)
          .build();
      given(outingRepository.findTeacherReceivedPage(
          eq(TEACHER_ID), eq(TODAY), eq(TODAY), isNull(), isNull(), eq(TODAY), eq(NOW),
          any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(pending), PageRequest.of(0, 20), 1));

      PageResponse<OutingResponse> response = outingService.getReceivedOutings(
          TEACHER_ID, OutingQueryPeriod.TODAY,
          null, null, null, 0, 20, TODAY, NOW);

      assertThat(response.content()).hasSize(1);
      assertThat(response.content().get(0).status()).isEqualTo(OutingStatus.PENDING);
    }

    @Test
    @DisplayName("리포지토리가 돌려준 Page 메타데이터를 그대로 응답에 담는다")
    void reflectsRepositoryPageMetadata() {
      List<Outing> pageContent = new java.util.ArrayList<>();
      for (int i = 0; i < 2; i++) {
        pageContent.add(Outing.builder()
            .id(800L + i)
            .code("PAGECODE" + i)
            .student(student())
            .teacher(teacher())
            .reason("치과 진료")
            .outingDate(TODAY)
            .timeSlot(OutingTimeSlot.LUNCH)
            .startTime(LocalTime.of(12, 30))
            .endTime(LocalTime.of(13, 40))
            .status(OutingStatus.PENDING)
            .build());
      }
      // DB가 size=2로 이미 자른 3건 중 첫 페이지라고 가정 — 슬라이싱 자체는 이제 DB 책임이라
      // 서비스는 Page가 돌려준 메타데이터를 그대로 옮기기만 하는지 검증한다.
      given(outingRepository.findTeacherReceivedPage(
          eq(TEACHER_ID), eq(TODAY), eq(TODAY), isNull(), isNull(), eq(TODAY), eq(NOW),
          any(Pageable.class)))
          .willReturn(new PageImpl<>(pageContent, PageRequest.of(0, 2), 3));

      PageResponse<OutingResponse> response = outingService.getReceivedOutings(
          TEACHER_ID, OutingQueryPeriod.TODAY,
          null, null, null, 0, 2, TODAY, NOW);

      assertThat(response.content()).hasSize(2);
      assertThat(response.totalElements()).isEqualTo(3);
      assertThat(response.totalPages()).isEqualTo(2);
      assertThat(response.hasNext()).isTrue();
    }
  }

  @Nested
  @DisplayName("getActiveOutings")
  class GetActiveOutings {

    private Outing departedOuting(Long id, LocalDateTime departedAt, String profileImageKey) {
      User departedStudent = User.builder()
          .id(STUDENT_ID)
          .gbsw(Gbsw.builder()
              .type(GbswType.STUDENT)
              .name("홍길동")
              .phoneNumber("01011112222")
              .grade(3)
              .classNo(4)
              .number(12)
              .build())
          .loginId("student1")
          .passwordHash("hash")
          .name("길동이")
          .phoneNumber("01011112222")
          .profileImageKey(profileImageKey)
          .build();
      return Outing.builder()
          .id(id)
          .code("ACTIVECODE" + id)
          .student(departedStudent)
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(TODAY)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(LocalTime.of(12, 30))
          .endTime(LocalTime.of(13, 40))
          .status(OutingStatus.DEPARTED)
          .departedAt(departedAt)
          .build();
    }

    @Test
    @DisplayName("DEPARTED 상태만 조회하고, departedAt 오름차순 + id 보조 정렬을 요청한다")
    void queriesDepartedStatusWithDepartedAtAndIdSort() {
      Outing outing = departedOuting(900L, LocalDateTime.of(2026, 8, 10, 12, 31), null);
      ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
      given(outingRepository.findByStatus(eq(OutingStatus.DEPARTED), pageableCaptor.capture()))
          .willReturn(new PageImpl<>(List.of(outing), PageRequest.of(0, 20), 1));

      PageResponse<OutingActiveResponse> response = outingService.getActiveOutings(0, 20);

      assertThat(response.content()).hasSize(1);
      assertThat(response.content().get(0).code()).isEqualTo("ACTIVECODE900");
      List<org.springframework.data.domain.Sort.Order> orders =
          pageableCaptor.getValue().getSort().stream().toList();
      assertThat(orders).extracting(org.springframework.data.domain.Sort.Order::getProperty)
          .containsExactly("departedAt", "id");
    }

    @Test
    @DisplayName("결과 없을 때 빈 배열을 반환한다(null 아님)")
    void returnsEmptyListNotNullWhenNoActiveOutings() {
      given(outingRepository.findByStatus(eq(OutingStatus.DEPARTED), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

      PageResponse<OutingActiveResponse> response = outingService.getActiveOutings(0, 20);

      assertThat(response.content()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("프로필 이미지 키가 없으면 studentProfileImageUrl은 null이다")
    void nullProfileImageUrlWhenKeyMissing() {
      Outing outing = departedOuting(901L, LocalDateTime.of(2026, 8, 10, 12, 31), null);
      given(outingRepository.findByStatus(eq(OutingStatus.DEPARTED), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(outing), PageRequest.of(0, 20), 1));

      PageResponse<OutingActiveResponse> response = outingService.getActiveOutings(0, 20);

      assertThat(response.content().get(0).studentProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("프로필 이미지 키가 있으면 presigned URL을 생성한다")
    void generatesProfileImageUrlWhenKeyPresent() {
      Outing outing =
          departedOuting(902L, LocalDateTime.of(2026, 8, 10, 12, 31), "profile/1/abc.jpg");
      given(outingRepository.findByStatus(eq(OutingStatus.DEPARTED), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(outing), PageRequest.of(0, 20), 1));
      given(r2FileService.generateDownloadUrl("profile/1/abc.jpg"))
          .willReturn("https://r2.example.com/profile/1/abc.jpg?X-Amz-Signature=...");

      PageResponse<OutingActiveResponse> response = outingService.getActiveOutings(0, 20);

      assertThat(response.content().get(0).studentProfileImageUrl())
          .isEqualTo("https://r2.example.com/profile/1/abc.jpg?X-Amz-Signature=...");
    }

    @Test
    @DisplayName("리포지토리가 돌려준 Page 메타데이터를 그대로 응답에 담는다")
    void reflectsRepositoryPageMetadata() {
      List<Outing> pageContent = List.of(
          departedOuting(903L, LocalDateTime.of(2026, 8, 10, 8, 0), null),
          departedOuting(904L, LocalDateTime.of(2026, 8, 10, 8, 30), null));
      given(outingRepository.findByStatus(eq(OutingStatus.DEPARTED), any(Pageable.class)))
          .willReturn(new PageImpl<>(pageContent, PageRequest.of(0, 2), 3));

      PageResponse<OutingActiveResponse> response = outingService.getActiveOutings(0, 2);

      assertThat(response.content()).hasSize(2);
      assertThat(response.totalElements()).isEqualTo(3);
      assertThat(response.totalPages()).isEqualTo(2);
      assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("page가 음수면 거부한다")
    void rejectsWhenPageNegative() {
      assertThatThrownBy(() -> outingService.getActiveOutings(-1, 20))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_PAGE_PARAMS);
    }

    @Test
    @DisplayName("size가 100을 초과하면 거부한다")
    void rejectsWhenSizeTooLarge() {
      assertThatThrownBy(() -> outingService.getActiveOutings(0, 101))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.INVALID_PAGE_PARAMS);
    }
  }

  @Nested
  @DisplayName("getOutingDetail")
  class GetOutingDetail {

    private static final String OUTING_CODE = "8A1zx9202n";

    private Outing outing() {
      return Outing.builder()
          .id(500L)
          .code(OUTING_CODE)
          .student(student())
          .teacher(teacher())
          .reason("치과 진료")
          .outingDate(TODAY)
          .timeSlot(OutingTimeSlot.LUNCH)
          .startTime(LocalTime.of(12, 30))
          .endTime(LocalTime.of(13, 40))
          .status(OutingStatus.PENDING)
          .build();
    }

    @Test
    @DisplayName("존재하지 않는 code면 거부한다")
    void rejectsWhenOutingNotFound() {
      given(outingRepository.findByCode("NOPE")).willReturn(Optional.empty());

      assertThatThrownBy(() ->
          outingService.getOutingDetail(STUDENT_ID, "NOPE", TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.OUTING_NOT_FOUND);
    }

    @Test
    @DisplayName("신청 학생 본인이면 조회할 수 있다")
    void allowsStudentOwner() {
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing()));

      OutingResponse response =
          outingService.getOutingDetail(STUDENT_ID, OUTING_CODE, TODAY, NOW);

      assertThat(response.code()).isEqualTo(OUTING_CODE);
    }

    @Test
    @DisplayName("담당 선생님 본인이면 조회할 수 있다")
    void allowsAssignedTeacher() {
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing()));

      OutingResponse response =
          outingService.getOutingDetail(TEACHER_ID, OUTING_CODE, TODAY, NOW);

      assertThat(response.code()).isEqualTo(OUTING_CODE);
    }

    @Test
    @DisplayName("DISCIPLINE 역할이면 무관한 사람이어도 조회할 수 있다")
    void allowsDisciplineRole() {
      Long disciplineUserId = 77L;
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing()));
      given(userRoleRepository.findRoleCodesByUserId(disciplineUserId))
          .willReturn(List.of("DISCIPLINE"));

      OutingResponse response =
          outingService.getOutingDetail(disciplineUserId, OUTING_CODE, TODAY, NOW);

      assertThat(response.code()).isEqualTo(OUTING_CODE);
    }

    @Test
    @DisplayName("ADMIN 역할이면 무관한 사람이어도 조회할 수 있다")
    void allowsAdminRole() {
      Long adminUserId = 88L;
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing()));
      given(userRoleRepository.findRoleCodesByUserId(adminUserId))
          .willReturn(List.of("ADMIN"));

      OutingResponse response =
          outingService.getOutingDetail(adminUserId, OUTING_CODE, TODAY, NOW);

      assertThat(response.code()).isEqualTo(OUTING_CODE);
    }

    @Test
    @DisplayName("TEACHER 역할이면 담당 아니어도 조회할 수 있다(#96)")
    void allowsAnyTeacherRole() {
      Long otherTeacherUserId = 66L;
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing()));
      given(userRoleRepository.findRoleCodesByUserId(otherTeacherUserId))
          .willReturn(List.of("TEACHER"));

      OutingResponse response =
          outingService.getOutingDetail(otherTeacherUserId, OUTING_CODE, TODAY, NOW);

      assertThat(response.code()).isEqualTo(OUTING_CODE);
    }

    @Test
    @DisplayName("관계 없는 사용자면 거부한다")
    void rejectsUnrelatedUser() {
      Long unrelatedUserId = 99L;
      given(outingRepository.findByCode(OUTING_CODE)).willReturn(Optional.of(outing()));
      given(userRoleRepository.findRoleCodesByUserId(unrelatedUserId)).willReturn(List.of());

      assertThatThrownBy(() ->
          outingService.getOutingDetail(unrelatedUserId, OUTING_CODE, TODAY, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ACCESS_DENIED);
    }
  }
}
