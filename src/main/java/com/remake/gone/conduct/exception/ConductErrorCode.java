package com.remake.gone.conduct.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Conduct(상/벌점) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code CONDUCT_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum ConductErrorCode implements ErrorCode {

  /** 상/벌점 기록을 찾을 수 없습니다. */
  RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "CONDUCT_001", "상/벌점 기록을 찾을 수 없습니다."),

  /** 본인이 부여한 기록만 처리할 수 있습니다. */
  NOT_RECORD_OWNER(HttpStatus.FORBIDDEN, "CONDUCT_002", "본인이 부여한 기록만 처리할 수 있습니다."),

  /** 이미 취소된 기록입니다. */
  ALREADY_CANCELED(HttpStatus.CONFLICT, "CONDUCT_003", "이미 취소된 기록입니다."),

  /** 존재하지 않거나 비활성화된 카테고리입니다. */
  CATEGORY_NOT_FOUND_OR_INACTIVE(HttpStatus.BAD_REQUEST, "CONDUCT_004",
      "존재하지 않거나 비활성화된 카테고리입니다."),

  /** 대상 학생을 찾을 수 없습니다. */
  STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "CONDUCT_005", "대상 학생을 찾을 수 없습니다."),

  /** 대상 사용자가 학생 역할이 아닙니다. */
  NOT_STUDENT_ROLE(HttpStatus.BAD_REQUEST, "CONDUCT_006", "대상 사용자가 학생 역할이 아닙니다."),

  /** 페이지 파라미터가 유효하지 않습니다. */
  INVALID_PAGE(HttpStatus.BAD_REQUEST, "CONDUCT_007", "페이지 파라미터가 유효하지 않습니다."),

  /** 날짜 범위 파라미터가 유효하지 않습니다. */
  INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "CONDUCT_008", "날짜 범위 파라미터가 유효하지 않습니다."),

  /** 상/벌점 요청을 찾을 수 없습니다. */
  REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "CONDUCT_009", "상/벌점 요청을 찾을 수 없습니다."),

  /** 본인이 등록한 요청만 취소할 수 있습니다. */
  REQUEST_CANCEL_FORBIDDEN(HttpStatus.FORBIDDEN, "CONDUCT_010", "본인이 등록한 요청만 취소할 수 있습니다."),

  /** PENDING 상태의 요청만 취소할 수 있습니다. */
  REQUEST_NOT_CANCELLABLE(HttpStatus.CONFLICT, "CONDUCT_011", "PENDING 상태의 요청만 취소할 수 있습니다."),

  /** 배정 대상자를 찾을 수 없습니다. */
  ASSIGNEE_NOT_FOUND(HttpStatus.NOT_FOUND, "CONDUCT_012", "배정 대상자를 찾을 수 없습니다."),

  /** 배정 대상자가 TEACHER 또는 ADMIN 역할이 아닙니다. */
  ASSIGNEE_INVALID_ROLE(HttpStatus.BAD_REQUEST, "CONDUCT_013",
      "배정 대상자가 TEACHER 또는 ADMIN 역할이 아닙니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  ConductErrorCode(HttpStatus status, String code, String defaultMessage) {
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
