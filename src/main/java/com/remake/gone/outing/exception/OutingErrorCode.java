package com.remake.gone.outing.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 외출(Outing) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code OUTING_NNN} (NNN은 3자리 순번). #29에서
 * {@code 001}/{@code 002}/{@code 003}/{@code 011}/{@code 012}를, #30(승인)/#31(거절)에서
 * {@code 004}~{@code 006}을, #41(조회)에서 {@code 007}/{@code 013}~{@code 015}를 채웠다
 * (승인/거절이 같은 원인 체계를 공유해 코드를 그대로 재사용한다). 출발/도착/위치 관련
 * 코드는 후속 이슈에서 추가한다.
 *
 * @see ErrorCode
 */
public enum OutingErrorCode implements ErrorCode {

  /** 신청 가능한 날짜(오늘~이번 주 금요일)가 아니거나, 이미 그 시간대 시작 시각이 지났습니다. */
  INVALID_DATE_OR_TIME(HttpStatus.BAD_REQUEST, "OUTING_001", "신청 가능한 날짜 또는 시간이 아닙니다."),

  /** 지정한 담당 선생님을 찾을 수 없거나 TEACHER 역할이 아닙니다. */
  TEACHER_NOT_FOUND(HttpStatus.BAD_REQUEST, "OUTING_002", "지정한 선생님을 찾을 수 없습니다."),

  /** 같은 날짜에 시간이 겹치는 활성(PENDING/APPROVED/DEPARTED) 외출증이 이미 있습니다. */
  TIME_OVERLAP(HttpStatus.CONFLICT, "OUTING_003", "같은 시간대에 이미 진행 중인 외출증이 있습니다."),

  /** 호출한 선생님이 그 외출증에 지정된 담당 선생님이 아닙니다. */
  TEACHER_MISMATCH(HttpStatus.FORBIDDEN, "OUTING_004", "본인에게 지정된 외출증만 처리할 수 있습니다."),

  /** 이미 PENDING 상태가 아닌(승인/거절/출발/도착 처리된) 외출증입니다. */
  ALREADY_PROCESSED(HttpStatus.CONFLICT, "OUTING_005", "지금 상태에서는 처리할 수 없는 요청입니다."),

  /** 요청한 code에 해당하는 외출증을 찾을 수 없습니다. */
  OUTING_NOT_FOUND(HttpStatus.NOT_FOUND, "OUTING_006", "외출증을 찾을 수 없습니다."),

  /** 신청 학생 본인/담당 선생님/DISCIPLINE/ADMIN 중 어느 조건에도 해당하지 않습니다(#41). */
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "OUTING_007", "이 외출증에 접근할 권한이 없습니다."),

  /** 커스텀 시간대가 허용 범위(08:40~20:30) 밖이거나, 종료 시각이 시작 시각보다 빠르거나 같습니다. */
  INVALID_CUSTOM_TIME_RANGE(
      HttpStatus.BAD_REQUEST, "OUTING_011", "커스텀 시간대는 08:40~20:30 범위 안이어야 합니다."),

  /** 외출증 신청은 STUDENT 역할을 가진 계정만 할 수 있습니다. */
  STUDENT_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "OUTING_012", "학생만 외출증을 신청할 수 있습니다."),

  /** 조회 시작일(dateFrom)이 종료일(dateTo)보다 늦습니다(#41, period=CUSTOM일 때). */
  INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "OUTING_013", "조회 시작일이 종료일보다 늦습니다."),

  /** period/dateFrom/dateTo 조합이 모순됩니다(#41) — CUSTOM인데 날짜가 없거나, CUSTOM이 아닌데
   *  날짜가 같이 왔습니다. */
  INVALID_PERIOD_PARAMS(
      HttpStatus.BAD_REQUEST, "OUTING_014", "조회 기간 파라미터 조합이 올바르지 않습니다."),

  /** page가 음수이거나 size가 1~100 범위 밖입니다(#41). */
  INVALID_PAGE_PARAMS(
      HttpStatus.BAD_REQUEST, "OUTING_015", "페이지 파라미터가 올바르지 않습니다(page>=0, 1<=size<=100).");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  OutingErrorCode(HttpStatus status, String code, String defaultMessage) {
    this.httpStatus = status;
    this.code = code;
    this.defaultMessage = defaultMessage;
  }

  @Override
  public HttpStatus getStatus() {
    return httpStatus;
  }

  @Override
  public String getCode() {
    return code;
  }

  @Override
  public String getDefaultMessage() {
    return defaultMessage;
  }
}
