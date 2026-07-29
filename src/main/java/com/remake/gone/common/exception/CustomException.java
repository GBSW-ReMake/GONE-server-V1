package com.remake.gone.common.exception;

/**
 * 애플리케이션 전역에서 사용하는 커스텀 런타임 예외.
 *
 * <p>{@link ErrorCode}를 기반으로 생성되며, {@link GlobalExceptionHandler}에서 일관된 응답으로 변환됩니다.
 *
 * <p>사용 예시:
 *
 * <pre>{@code
 * throw new CustomException(CommonErrorCode.NOT_FOUND);
 * }</pre>
 *
 * @see ErrorCode
 * @see GlobalExceptionHandler
 */
public class CustomException extends RuntimeException {

  private final ErrorCode errorCode;

  /**
   * 에러 코드로 예외를 생성합니다.
   *
   * <p>예외 메시지는 {@link ErrorCode#getDefaultMessage()}로 자동 설정됩니다.
   *
   * @param errorCode 이 예외를 나타내는 에러 코드
   */
  public CustomException(ErrorCode errorCode) {
    super(errorCode.getDefaultMessage());
    this.errorCode = errorCode;
  }

  /**
   * 이 예외의 에러 코드를 반환합니다.
   *
   * @return 에러 코드
   */
  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
