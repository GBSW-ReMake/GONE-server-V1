package com.remake.gone.neis.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 나이스(NEIS) 오픈 API 연동 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code NEIS_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum NeisErrorCode implements ErrorCode {

  /** NEIS가 {@code ERROR-*} 응답을 반환했거나, 네트워크 자체가 실패한 경우. */
  EXTERNAL_API_ERROR(
      HttpStatus.BAD_GATEWAY, "NEIS_001", "외부 학교 정보 서비스와 통신 중 문제가 발생했습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  NeisErrorCode(HttpStatus status, String code, String defaultMessage) {
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
