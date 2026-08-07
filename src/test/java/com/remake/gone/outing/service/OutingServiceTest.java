package com.remake.gone.outing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.entity.Outing;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
  }

  @Nested
  @DisplayName("rejectOuting")
  class RejectOuting {

    private static final String OUTING_CODE = "8A1zx9202n";
    private static final String REJECTED_REASON = "지금은 상담 시간이라 곤란해요";

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
          outingService.rejectOuting(TEACHER_ID, OUTING_CODE, REJECTED_REASON);

      assertThat(response.status()).isEqualTo(OutingStatus.REJECTED);
      assertThat(response.rejectedReason()).isEqualTo(REJECTED_REASON);
      assertThat(outing.getRejectedReason()).isEqualTo(REJECTED_REASON);
      verify(outingRepository).save(outing);
    }

    @Test
    @DisplayName("존재하지 않는 code면 거부한다")
    void rejectsWhenOutingNotFound() {
      given(outingRepository.findByCode("NOPE")).willReturn(Optional.empty());

      assertThatThrownBy(() -> outingService.rejectOuting(TEACHER_ID, "NOPE", REJECTED_REASON))
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
          otherTeacherId, OUTING_CODE, REJECTED_REASON))
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
          TEACHER_ID, OUTING_CODE, REJECTED_REASON))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(OutingErrorCode.ALREADY_PROCESSED);
    }
  }
}
