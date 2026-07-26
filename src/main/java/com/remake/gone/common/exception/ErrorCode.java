package com.remake.gone.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인별 에러 코드를 정의하는 공통 인터페이스.
 *
 * 각 도메인의 에러 코드 enum은 이 인터페이스를 구현하여 HTTP 상태 코드, 에러 식별 코드, 기본 메시지를 제공합니다.
 *
 * @see AuthErrorCode
 */
public interface ErrorCode {

  /**
   * 이 에러에 해당하는 HTTP 상태 코드를 반환합니다.
   *
   * @return HTTP 상태 코드
   */
  HttpStatus getStatus();

  /**
   * 이 에러의 기본 설명 메시지를 반환합니다.
   *
   * @return 사용자에게 전달할 기본 메시지
   */
  String getDefaultMessage();

  /**
   * 이 에러의 고유 식별 코드를 반환합니다.
   *
   * <p>예) {@code "AUTH_001"}
   *
   * @return 에러 식별 코드 문자열
   */
  String getCode();
}
