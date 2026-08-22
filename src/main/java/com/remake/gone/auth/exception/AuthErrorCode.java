package com.remake.gone.auth.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 인증(Auth) 도메인에서 사용하는 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code AUTH_NNN} (NNN은 3자리 순번)
 *
 * @see ErrorCode
 */
public enum AuthErrorCode implements ErrorCode {

  /** 휴대폰 인증번호가 일치하지 않습니다. */
  INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "AUTH_001", "인증번호가 일치하지 않습니다."),

  /** 휴대폰 인증번호가 만료되었거나 발송된 적이 없습니다. */
  PHONE_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "AUTH_002", "인증번호가 만료되었습니다. 다시 요청해주세요."),

  /** 인증번호 확인 실패 횟수가 허용치를 초과했습니다. */
  TOO_MANY_VERIFICATION_ATTEMPTS(
      HttpStatus.TOO_MANY_REQUESTS, "AUTH_003", "인증 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."),

  /** 인증번호 재발송 쿨다운이 끝나지 않았습니다. */
  TOO_MANY_SMS_REQUESTS(
      HttpStatus.TOO_MANY_REQUESTS, "AUTH_004", "인증번호 재발송을 너무 많이 요청했습니다. 잠시 후 다시 시도해주세요."),

  /** 회원가입 티켓이 유효하지 않거나 만료되었습니다. */
  INVALID_OR_EXPIRED_TICKET(
      HttpStatus.BAD_REQUEST, "AUTH_005", "유효하지 않거나 만료된 티켓입니다. 인증을 다시 진행해주세요."),

  /** 티켓에 저장된 휴대폰 번호와 요청한 휴대폰 번호가 일치하지 않습니다. */
  PHONE_NUMBER_MISMATCH(HttpStatus.BAD_REQUEST, "AUTH_006", "인증된 휴대폰 번호와 일치하지 않습니다."),

  /** 로그인 ID가 없거나 비밀번호가 일치하지 않습니다. 계정 존재 여부를 유추할 수 없도록 동일 메시지를 사용합니다. */
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_007", "아이디 또는 비밀번호가 일치하지 않습니다."),

  /** Refresh Token이 위조/만료되었거나, Redis에 저장된 최신 토큰과 일치하지 않습니다(재사용 시도 포함). */
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_008", "유효하지 않거나 만료된 토큰입니다. 다시 로그인해주세요."),

  /** SMS 발송사(알리고) API 호출이 실패했거나, 발송사가 실패 응답을 반환한 경우. */
  SMS_SEND_FAILED(HttpStatus.BAD_GATEWAY, "AUTH_009", "문자 발송에 실패했습니다. 잠시 후 다시 시도해주세요.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  AuthErrorCode(HttpStatus status, String code, String defaultMessage) {
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
