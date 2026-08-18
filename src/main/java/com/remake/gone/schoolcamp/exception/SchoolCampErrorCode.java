package com.remake.gone.schoolcamp.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * SchoolCamp(스쿨캠핑) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code SCHOOLCAMP_NNN} (NNN은 3자리 순번). 번호는 마스터 기획서
 * (`docs/domain/schoolcamp/1_schoolcamp-domain.md`)의 전체 목록 중 실제로 구현되는 순서대로
 * 채운다 — #67에서는 005/006만 쓰인다.
 *
 * @see ErrorCode
 */
public enum SchoolCampErrorCode implements ErrorCode {

  /** 요청한 날짜 중 금/토/일이 포함되어 있습니다. */
  INVALID_CAMP_DATE(HttpStatus.BAD_REQUEST, "SCHOOLCAMP_005", "신청 가능한 날짜가 아닙니다."),

  /** 요청한 날짜 중 이미 세션이 등록된 날짜가 있습니다. */
  CAMP_DATE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "SCHOOLCAMP_006", "이미 등록된 날짜입니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  SchoolCampErrorCode(HttpStatus status, String code, String defaultMessage) {
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
