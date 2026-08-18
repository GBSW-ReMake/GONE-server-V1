package com.remake.gone.schoolcamp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.entity.SchoolCampSession;
import com.remake.gone.schoolcamp.enums.SchoolCampStatus;
import com.remake.gone.schoolcamp.exception.SchoolCampErrorCode;
import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
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

  @InjectMocks
  private SchoolCampService schoolCampService;

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
  }

  @Nested
  @DisplayName("getCalendar")
  class GetCalendar {

    @Test
    @DisplayName("taken_at이 있는 세션은 CLOSED, 없는 세션은 OPEN으로 반환하고 이름 필드는 항상 null이다")
    void returnsCalendarWithStatusAndNullNames() {
      SchoolCampSession openSession = SchoolCampSession.builder()
          .id(1L)
          .campDate(LocalDate.of(2026, 4, 6))
          .build();
      SchoolCampSession closedSession = SchoolCampSession.builder()
          .id(2L)
          .campDate(LocalDate.of(2026, 4, 10))
          .takenAt(LocalDateTime.of(2026, 3, 20, 9, 12))
          .build();
      given(sessionRepository.findByCampDateBetween(
          LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
          .willReturn(List.of(openSession, closedSession));

      List<SchoolCampCalendarResponse> result =
          schoolCampService.getCalendar(YearMonth.of(2026, 4));

      assertThat(result).containsExactly(
          new SchoolCampCalendarResponse(1L, "20260406", SchoolCampStatus.OPEN, null, null),
          new SchoolCampCalendarResponse(2L, "20260410", SchoolCampStatus.CLOSED, null, null));
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
}
