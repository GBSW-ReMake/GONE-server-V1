package com.remake.gone.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 여러 도메인에서 공통으로 사용하는 에러 코드.
 *
 * <p>도메인 특화 에러 코드가 필요한 경우 {@link ErrorCode}를 구현하는 별도 enum을 추가합니다.
 *
 * <p>코드 네이밍 규칙: {@code COMMON_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum CommonErrorCode implements ErrorCode {

  /** 요청 파라미터나 바디 형식이 올바르지 않습니다. */
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 형식이 올바르지 않습니다."),

  /** 인증 정보가 없거나 유효하지 않습니다. */
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_002", "인증이 필요합니다."),

  /** 해당 리소스에 대한 접근 권한이 없습니다. */
  FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_003", "접근 권한이 없습니다."),

  /** 요청한 리소스를 찾을 수 없습니다. */
  NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_004", "요청한 리소스를 찾을 수 없습니다."),

  /** 허용되지 않는 HTTP 메서드로 요청했습니다. */
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_005", "허용되지 않는 HTTP 메서드입니다."),

  /** 동일한 리소스가 이미 존재합니다. */
  CONFLICT(HttpStatus.CONFLICT, "COMMON_006", "이미 존재하는 리소스입니다."),

  /** 서버 내부에서 처리할 수 없는 오류가 발생했습니다. */
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_007", "서버 내부 오류가 발생했습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  CommonErrorCode(HttpStatus status, String code, String defaultMessage) {
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
