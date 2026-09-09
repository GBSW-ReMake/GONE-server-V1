package com.remake.gone.schoolcamp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.service.NotificationService;
import com.remake.gone.schoolcamp.dto.SchoolCampWaitlistResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampWaitlistStatusResponse;
import com.remake.gone.schoolcamp.entity.SchoolCampWaitlist;
import com.remake.gone.schoolcamp.exception.SchoolCampErrorCode;
import com.remake.gone.schoolcamp.repository.SchoolCampWaitlistRepository;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link SchoolCampWaitlistService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class SchoolCampWaitlistServiceTest {

  private static final Long STUDENT_ID = 101L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 10, 9, 0);
  private static final LocalDate MONTH_START = LocalDate.of(2026, 4, 1);

  @Mock
  private SchoolCampWaitlistRepository waitlistRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private NotificationService notificationService;

  @InjectMocks
  private SchoolCampWaitlistService waitlistService;

  private SchoolCampWaitlist waitlist(LocalDateTime registeredAt, LocalDateTime cancelledAt) {
    User student = User.builder().id(STUDENT_ID).build();
    return SchoolCampWaitlist.builder()
        .id(1L)
        .studentUser(student)
        .month(MONTH_START)
        .registeredAt(registeredAt)
        .cancelledAt(cancelledAt)
        .build();
  }

  @Nested
  @DisplayName("register")
  class Register {

    @Test
    @DisplayName("등록된 적 없으면 새로 등록한다")
    void registersNewWhenNoExistingRow() {
      given(waitlistRepository.findByStudentUserIdAndMonth(STUDENT_ID, MONTH_START))
          .willReturn(Optional.empty());
      given(userRepository.getReferenceById(STUDENT_ID))
          .willReturn(User.builder().id(STUDENT_ID).build());

      SchoolCampWaitlistResponse response = waitlistService.register(STUDENT_ID, NOW);

      assertThat(response.month()).isEqualTo("202604");
      assertThat(response.registeredAt()).isEqualTo(NOW);
      verify(waitlistRepository).save(argThatWaitlistWith(MONTH_START, NOW, null));
    }

    @Test
    @DisplayName("이미 유효한 등록이 있으면 409를 던진다")
    void throwsWhenAlreadyRegistered() {
      given(waitlistRepository.findByStudentUserIdAndMonth(STUDENT_ID, MONTH_START))
          .willReturn(Optional.of(waitlist(NOW.minusDays(1), null)));

      assertThatThrownBy(() -> waitlistService.register(STUDENT_ID, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.ALREADY_REGISTERED_WAITLIST);

      verify(waitlistRepository, never()).save(any());
    }

    @Test
    @DisplayName("취소됐던 같은 달 행이 있으면 재활성화한다")
    void reactivatesCancelledRow() {
      SchoolCampWaitlist cancelled = waitlist(NOW.minusDays(10), NOW.minusDays(5));
      given(waitlistRepository.findByStudentUserIdAndMonth(STUDENT_ID, MONTH_START))
          .willReturn(Optional.of(cancelled));

      SchoolCampWaitlistResponse response = waitlistService.register(STUDENT_ID, NOW);

      assertThat(cancelled.getCancelledAt()).isNull();
      assertThat(cancelled.getRegisteredAt()).isEqualTo(NOW);
      assertThat(response.registeredAt()).isEqualTo(NOW);
      verify(waitlistRepository).save(cancelled);
      verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("동시 등록 레이스로 유니크 제약을 위반하면 409를 던진다")
    void throwsOnConcurrentRegisterRace() {
      given(waitlistRepository.findByStudentUserIdAndMonth(STUDENT_ID, MONTH_START))
          .willReturn(Optional.empty());
      given(userRepository.getReferenceById(STUDENT_ID))
          .willReturn(User.builder().id(STUDENT_ID).build());
      given(waitlistRepository.save(any()))
          .willThrow(new DataIntegrityViolationException("duplicate"));

      assertThatThrownBy(() -> waitlistService.register(STUDENT_ID, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.ALREADY_REGISTERED_WAITLIST);
    }

    private SchoolCampWaitlist argThatWaitlistWith(
        LocalDate month, LocalDateTime registeredAt, LocalDateTime cancelledAt) {
      return argThat(w -> w.getMonth().equals(month)
          && w.getRegisteredAt().equals(registeredAt)
          && w.getCancelledAt() == cancelledAt);
    }
  }

  @Nested
  @DisplayName("cancel")
  class Cancel {

    @Test
    @DisplayName("정상 취소 시 cancelledAt을 채운다")
    void cancelsSuccessfully() {
      SchoolCampWaitlist active = waitlist(NOW.minusDays(1), null);
      given(waitlistRepository.findByStudentUserIdAndMonthAndCancelledAtIsNull(
          STUDENT_ID, MONTH_START)).willReturn(Optional.of(active));

      waitlistService.cancel(STUDENT_ID, NOW);

      assertThat(active.getCancelledAt()).isEqualTo(NOW);
      verify(waitlistRepository).save(active);
    }

    @Test
    @DisplayName("유효한 등록이 없으면 404를 던진다")
    void throwsWhenNotFound() {
      given(waitlistRepository.findByStudentUserIdAndMonthAndCancelledAtIsNull(
          STUDENT_ID, MONTH_START)).willReturn(Optional.empty());

      assertThatThrownBy(() -> waitlistService.cancel(STUDENT_ID, NOW))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(SchoolCampErrorCode.WAITLIST_NOT_FOUND);
    }
  }

  @Nested
  @DisplayName("getStatus")
  class GetStatus {

    @Test
    @DisplayName("유효한 등록이 있으면 registered=true를 반환한다")
    void returnsRegisteredTrue() {
      SchoolCampWaitlist active = waitlist(NOW.minusDays(1), null);
      given(waitlistRepository.findByStudentUserIdAndMonthAndCancelledAtIsNull(
          STUDENT_ID, MONTH_START)).willReturn(Optional.of(active));

      SchoolCampWaitlistStatusResponse response = waitlistService.getStatus(STUDENT_ID, NOW);

      assertThat(response.registered()).isTrue();
      assertThat(response.registeredAt()).isEqualTo(active.getRegisteredAt());
    }

    @Test
    @DisplayName("유효한 등록이 없으면 registered=false를 반환한다")
    void returnsRegisteredFalse() {
      given(waitlistRepository.findByStudentUserIdAndMonthAndCancelledAtIsNull(
          STUDENT_ID, MONTH_START)).willReturn(Optional.empty());

      SchoolCampWaitlistStatusResponse response = waitlistService.getStatus(STUDENT_ID, NOW);

      assertThat(response.registered()).isFalse();
      assertThat(response.registeredAt()).isNull();
    }
  }

  @Nested
  @DisplayName("notifyForMonth")
  class NotifyForMonth {

    @Test
    @DisplayName("그 달 유효한 대기자 전원에게 알림을 보낸다")
    void notifiesAllActiveWaitlistersInMonth() {
      SchoolCampWaitlist first = waitlist(NOW.minusDays(3), null);
      SchoolCampWaitlist second = waitlist(NOW.minusDays(2), null);
      given(waitlistRepository.findByMonthAndCancelledAtIsNull(MONTH_START))
          .willReturn(List.of(first, second));

      waitlistService.notifyForMonth(YearMonth.of(2026, 4));

      verify(notificationService, times(2)).send(
          eq(STUDENT_ID), eq("스쿨캠핑 자리가 났어요!"),
          eq("2026년 4월 스쿨캠핑에 취소로 빈 자리가 생겼어요. 캘린더에서 확인하고 신청해보세요!"),
          eq(NotificationType.SCHOOLCAMP));
    }

    @Test
    @DisplayName("대기자가 없으면 알림을 보내지 않는다")
    void doesNothingWhenNoWaitlisters() {
      given(waitlistRepository.findByMonthAndCancelledAtIsNull(MONTH_START))
          .willReturn(List.of());

      waitlistService.notifyForMonth(YearMonth.of(2026, 4));

      verifyNoInteractions(notificationService);
    }
  }
}
