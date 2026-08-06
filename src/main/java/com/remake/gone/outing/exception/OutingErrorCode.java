package com.remake.gone.outing.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 외출(Outing) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code OUTING_NNN} (NNN은 3자리 순번). 이 이슈(#29)에서는
 * {@code 001}/{@code 002}/{@code 003}/{@code 011}/{@code 012}만 채우고, 승인/거절/출발/도착/
 * 위치 관련 코드({@code 004}~{@code 010})는 후속 이슈(#30/#31/...)에서 추가한다.
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

  /** 커스텀 시간대가 허용 범위(08:40~20:30) 밖이거나, 종료 시각이 시작 시각보다 빠르거나 같습니다. */
  INVALID_CUSTOM_TIME_RANGE(
      HttpStatus.BAD_REQUEST, "OUTING_011", "커스텀 시간대는 08:40~20:30 범위 안이어야 합니다."),

  /** 외출증 신청은 STUDENT 역할을 가진 계정만 할 수 있습니다. */
  STUDENT_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "OUTING_012", "학생만 외출증을 신청할 수 있습니다.");

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
