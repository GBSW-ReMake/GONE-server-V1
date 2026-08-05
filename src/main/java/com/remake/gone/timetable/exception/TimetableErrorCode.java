package com.remake.gone.timetable.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 시간표 조회 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code TIMETABLE_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum TimetableErrorCode implements ErrorCode {

  /**
   * 3학년 학과 매핑({@code TimetableService.GRADE_3_DEPARTMENT_MAP})에 없는 {@code class_no}인
   * 경우. 학과별 반 개수가 바뀌었는데 매핑을 갱신하지 않았을 때 발생할 수 있다(이슈 #27 참고).
   */
  UNKNOWN_CLASS_MAPPING(
      HttpStatus.INTERNAL_SERVER_ERROR, "TIMETABLE_001",
      "학급 정보를 확인할 수 없습니다. 관리자에게 문의해주세요.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  TimetableErrorCode(HttpStatus status, String code, String defaultMessage) {
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
