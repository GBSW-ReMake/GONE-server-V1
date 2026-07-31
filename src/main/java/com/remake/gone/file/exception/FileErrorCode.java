package com.remake.gone.file.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 파일(File) 도메인에서 사용하는 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code FILE_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum FileErrorCode implements ErrorCode {

  /** 업로드 완료 확인 시 실제 저장소(R2)에 파일이 존재하지 않습니다. */
  UPLOAD_CONFIRM_FAILED(HttpStatus.BAD_REQUEST, "FILE_001", "업로드된 파일을 확인할 수 없습니다."),

  /** 허용되지 않는 파일 형식(Content-Type)입니다. */
  UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "FILE_002", "지원하지 않는 파일 형식입니다."),

  /** 파일 크기가 허용 최대치를 초과했습니다. */
  FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "FILE_003", "파일 크기가 허용 범위를 초과했습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  FileErrorCode(HttpStatus status, String code, String defaultMessage) {
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
