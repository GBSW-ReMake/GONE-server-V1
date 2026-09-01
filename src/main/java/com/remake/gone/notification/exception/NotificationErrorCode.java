package com.remake.gone.notification.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 알림(Notification) 도메인 에러 코드.
 */
public enum NotificationErrorCode implements ErrorCode {

  /** 페이지 조회 조건이 올바르지 않습니다. */
  INVALID_PAGE_PARAMS(
      HttpStatus.BAD_REQUEST,
      "NOTIFICATION_003",
      "페이지 조회 조건이 올바르지 않습니다(page>=0, 1<=size<=100).");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  NotificationErrorCode(HttpStatus httpStatus, String code, String defaultMessage) {
    this.httpStatus = httpStatus;
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
