package com.remake.gone.common.exception;

import com.remake.gone.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리기.
 *
 * <p>애플리케이션 전역에서 발생하는 예외를 처리하여
 * 일관된 {@link ApiResponse} 형태로 응답을 반환합니다.
 *
 * <p>처리 우선순위: {@link CustomException} → Spring MVC 예외 → {@link Exception} (폴백)
 *
 * @see CustomException
 * @see CommonErrorCode
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  /**
   * {@link CustomException} 처리.
   *
   * <p>에러 코드에 정의된 HTTP 상태와 메시지를 그대로 응답에 반영합니다.
   *
   * @param e 발생한 커스텀 예외
   * @return 에러 코드에 해당하는 HTTP 상태와 메시지를 담은 응답
   */
  @ExceptionHandler(CustomException.class)
  public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
    ErrorCode errorCode = e.getErrorCode();
    return ResponseEntity
        .status(errorCode.getStatus())
        .body(ApiResponse.fail(null, errorCode.getDefaultMessage()));
  }

  /**
   * {@link MethodArgumentNotValidException} 처리.
   *
   * <p>{@code @Valid} 검증 실패 시 발생하며, 첫 번째 필드 에러 메시지를 반환합니다.
   *
   * @param e 발생한 유효성 검증 예외
   * @return {@code 400 Bad Request} 응답
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e) {
    String message = e.getBindingResult()
        .getFieldErrors()
        .stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage())
        .findFirst()
        .orElse(CommonErrorCode.INVALID_REQUEST.getDefaultMessage());
    return ResponseEntity
        .status(CommonErrorCode.INVALID_REQUEST.getStatus())
        .body(ApiResponse.fail(null, message));
  }

  /**
   * {@link HttpRequestMethodNotSupportedException} 처리.
   *
   * <p>허용되지 않는 HTTP 메서드로 요청 시 발생합니다.
   *
   * @param e 발생한 예외
   * @return {@code 405 Method Not Allowed} 응답
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupported(
      HttpRequestMethodNotSupportedException e) {
    return ResponseEntity
        .status(CommonErrorCode.METHOD_NOT_ALLOWED.getStatus())
        .body(ApiResponse.fail(null, CommonErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage()));
  }

  /**
   * 처리되지 않은 모든 예외에 대한 폴백 처리기.
   *
   * <p>예상치 못한 서버 오류 발생 시 {@code 500 Internal Server Error}를 반환합니다.
   *
   * @param e 발생한 예외
   * @return {@code 500 Internal Server Error} 응답
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
    return ResponseEntity
        .status(CommonErrorCode.INTERNAL_SERVER_ERROR.getStatus())
        .body(ApiResponse.fail(null, CommonErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage()));
  }
}
