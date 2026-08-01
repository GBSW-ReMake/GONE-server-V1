package com.remake.gone.gbsw.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Gbsw(교내 명단) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code GBSW_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum GbswErrorCode implements ErrorCode {

  /** 회원가입 시 입력한 휴대폰 번호가 교내 명단에 존재하지 않습니다. */
  NOT_REGISTERED_PHONE_NUMBER(
      HttpStatus.NOT_FOUND, "GBSW_001", "명단에 등록되지 않은 번호입니다. 관리자에게 문의해주세요.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  GbswErrorCode(HttpStatus status, String code, String defaultMessage) {
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
