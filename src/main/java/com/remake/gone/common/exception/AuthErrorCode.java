package com.remake.gone.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 인증/인가 도메인 에러 코드.
 * 새 에러 코드 추가 시 아래 예시 형식을 따릅니다.
 * 코드 네이밍 규칙: {@code AUTH_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum AuthErrorCode implements ErrorCode {
  // INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_001", "아이디 또는 비밀번호가 올바르지 않습니다.");
  ;

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  AuthErrorCode(HttpStatus status, String code, String defaultMessage) {
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
