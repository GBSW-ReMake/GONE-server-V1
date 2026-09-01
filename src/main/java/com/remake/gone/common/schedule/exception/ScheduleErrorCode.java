package com.remake.gone.common.schedule.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 범용 이벤트 스케줄링 인프라(#120)의 관리자 모니터링/재시도 API(#126) 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code SCHEDULE_NNN} (NNN은 3자리 순번).
 *
 * @see ErrorCode
 */
public enum ScheduleErrorCode implements ErrorCode {

  /** 페이지 파라미터가 유효 범위를 벗어났습니다. */
  INVALID_PAGE(HttpStatus.BAD_REQUEST, "SCHEDULE_001", "페이지 요청이 올바르지 않습니다."),

  /** 해당 id의 스케줄 작업이 존재하지 않습니다. */
  TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHEDULE_002", "해당 스케줄 작업을 찾을 수 없습니다."),

  /** FAILED가 아닌 작업을 재시도하려고 했습니다. */
  NOT_FAILED(HttpStatus.CONFLICT, "SCHEDULE_003", "FAILED 상태인 작업만 재시도할 수 있습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  ScheduleErrorCode(HttpStatus httpStatus, String code, String defaultMessage) {
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
