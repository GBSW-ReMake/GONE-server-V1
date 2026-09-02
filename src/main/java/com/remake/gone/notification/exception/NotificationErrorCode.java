package com.remake.gone.notification.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 알림(Notification) 도메인 에러 코드.
 */
public enum NotificationErrorCode implements ErrorCode {

  /** 알림을 찾을 수 없습니다. */
  NOTIFICATION_NOT_FOUND(
      HttpStatus.NOT_FOUND,
      "NOTIFICATION_001",
      "알림을 찾을 수 없습니다."),

  /** 다른 사용자의 알림은 읽음 처리할 수 없습니다. */
  NOTIFICATION_ACCESS_DENIED(
      HttpStatus.FORBIDDEN,
      "NOTIFICATION_002",
      "본인 알림만 읽음 처리할 수 있습니다."),

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
