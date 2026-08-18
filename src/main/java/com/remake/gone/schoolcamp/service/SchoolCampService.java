package com.remake.gone.schoolcamp.service;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.entity.SchoolCampSession;
import com.remake.gone.schoolcamp.enums.SchoolCampStatus;
import com.remake.gone.schoolcamp.exception.SchoolCampErrorCode;
import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스쿨캠핑(SchoolCamp) 세션 등록/캘린더 조회 비즈니스 로직을 처리하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class SchoolCampService {

  private static final DateTimeFormatter YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

  /** 스쿨캠핑을 열 수 없는 요일(금/토/일). */
  private static final Set<DayOfWeek> CLOSED_DAYS_OF_WEEK =
      Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

  private final SchoolCampSessionRepository sessionRepository;

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
   * 특정 달의 스쿨캠핑 캘린더(날짜별 점유 상태)를 조회합니다.
   *
   * <p>신청 도메인 모델({@code SchoolCampApplication})이 아직 없어(#68에서 추가), 담당
   * 선생님/대표 신청자 표시 이름은 이 메서드에서는 항상 {@code null}을 반환합니다.
   *
   * @param month 조회할 달
   * @return 그 달의 세션별 캘린더 정보
   */
  @Transactional(readOnly = true)
  public List<SchoolCampCalendarResponse> getCalendar(YearMonth month) {
    List<SchoolCampSession> sessions =
        sessionRepository.findByCampDateBetween(month.atDay(1), month.atEndOfMonth());

    return sessions.stream()
        .map(session -> new SchoolCampCalendarResponse(
            session.getId(),
            session.getCampDate().format(YMD_FORMATTER),
            session.getTakenAt() != null ? SchoolCampStatus.CLOSED : SchoolCampStatus.OPEN,
            null,
            null))
        .toList();
  }
}
