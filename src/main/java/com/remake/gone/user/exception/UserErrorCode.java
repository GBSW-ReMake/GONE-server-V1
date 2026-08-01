package com.remake.gone.user.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * User(회원) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code USER_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum UserErrorCode implements ErrorCode {

  /** 이미 해당 명단(Gbsw)에 연결된 계정이 존재합니다. */
  ALREADY_REGISTERED(HttpStatus.CONFLICT, "USER_001", "이미 가입된 계정입니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  UserErrorCode(HttpStatus status, String code, String defaultMessage) {
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
