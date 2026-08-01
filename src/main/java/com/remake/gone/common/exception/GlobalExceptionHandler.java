package com.remake.gone.common.exception;

import com.remake.gone.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
  public ResponseEntity<ApiResponse<Object>> handleCustomException(CustomException e) {
    ErrorCode errorCode = e.getErrorCode();
    return ResponseEntity
        .status(errorCode.getStatus())
        .body(ApiResponse.fail(e.getData(), errorCode.getDefaultMessage(), errorCode.getCode()));
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
        .body(ApiResponse.fail(null, message, CommonErrorCode.INVALID_REQUEST.getCode()));
  }

  /**
   * {@link ConstraintViolationException} 처리.
   *
   * <p>{@code @RequestBody}가 아니라 {@code @RequestParam}/{@code @PathVariable}에 붙인
   * 검증 애노테이션(예: {@code @NotBlank}, {@code @Pattern})이 실패했을 때 발생합니다.
   * ({@code @RequestBody} 검증 실패는 {@link MethodArgumentNotValidException}으로 별도 처리됨)
   * 컨트롤러 클래스에 {@code @Validated}가 붙어 있어야 이 예외가 던져집니다.
   *
   * @param e 발생한 제약 조건 위반 예외
   * @return {@code 400 Bad Request} 응답
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
      ConstraintViolationException e) {
    String message = e.getConstraintViolations()
        .stream()
        .findFirst()
        .map(violation -> violation.getMessage())
        .orElse(CommonErrorCode.INVALID_REQUEST.getDefaultMessage());
    return ResponseEntity
        .status(CommonErrorCode.INVALID_REQUEST.getStatus())
        .body(ApiResponse.fail(null, message, CommonErrorCode.INVALID_REQUEST.getCode()));
  }

  /**
   * {@link DataIntegrityViolationException} 처리.
   *
   * <p><b>레이스 컨디션(race condition) 배경.</b> {@code existsByXxx()}로 중복을 미리 확인한
   * 뒤 {@code save()}하는 "check-then-act" 패턴은, 그 확인과 저장 사이에 시간差이 존재하는 한
   * 근본적으로 동시성에 안전하지 않습니다. 예를 들어 같은 로그인 ID로 거의 동시에 두 요청이
   * 들어오면 아래와 같은 순서가 가능합니다.
   *
   * <pre>{@code
   * 요청 A: existsByLoginId("hong123") → false (아직 없음)
   * 요청 B: existsByLoginId("hong123") → false (A가 아직 save() 하기 전)
   * 요청 A: save(user)                 → 성공, hong123 저장됨
   * 요청 B: save(user)                 → UNIQUE(login_id) 위반 → DataIntegrityViolationException
   * }</pre>
   *
   * <p><b>이 레이스 자체는 애플리케이션 코드로 막을 필요가 없습니다.</b> 이미 DB의
   * {@code UNIQUE} 제약이 두 저장 시도 중 정확히 하나만 성공하도록 원자적으로 보장하기
   * 때문입니다. 락(비관적 락, 분산 락 등)을 추가로 걸어 "동시에 들어오지 못하게" 막는 건
   * 이미 DB가 해결한 문제를 애플리케이션 레벨에서 다시 푸는 것이라 불필요한 비용입니다.
   *
   * <p><b>그럼 이 핸들러가 하는 일은 무엇인가.</b> 데이터 정합성은 이미 안전하지만, "진 쪽"
   * 요청 입장에서는 자신이 통과한 애플리케이션 레벨 중복 검사({@code existsByXxx()})와 실제
   * DB 결과가 어긋나면서 처리되지 않은 예외가 그대로 터집니다. 이 핸들러가 없으면 그 예외는
   * 이 클래스 맨 아래의 {@link #handleException} 폴백으로 떨어져 {@code 500 Internal Server
   * Error}로 응답됩니다 — 하지만 실제로는 서버 오류가 아니라 "그 자원을 방금 다른 요청이
   * 선점했다"는 정상적인 비즈니스 상황이므로 {@code 409 Conflict}가 올바른 응답입니다.
   * 즉, 이 핸들러는 레이스를 막는 게 아니라, 레이스가 났을 때 DB가 이미 내린 판정을
   * 클라이언트에게 올바른 상태 코드로 정확히 전달하는 역할만 합니다.
   *
   * <p>{@code loginId}, {@code phone_number}, {@code gbsw_id} 등 unique 제약이 걸린 모든
   * 컬럼에 공통으로 적용되는 안전망이므로, 개별 서비스 로직에 예외 처리를 추가할 필요가
   * 없습니다.
   *
   * @param e 발생한 예외
   * @return {@code 409 Conflict} 응답
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
      DataIntegrityViolationException e) {
    return ResponseEntity
        .status(CommonErrorCode.CONFLICT.getStatus())
        .body(ApiResponse.fail(
            null,
            CommonErrorCode.CONFLICT.getDefaultMessage(),
            CommonErrorCode.CONFLICT.getCode()));
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
        .body(ApiResponse.fail(
            null,
            CommonErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(),
            CommonErrorCode.METHOD_NOT_ALLOWED.getCode()));
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
        .body(ApiResponse.fail(
            null,
            CommonErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage(),
            CommonErrorCode.INTERNAL_SERVER_ERROR.getCode()));
  }
}
