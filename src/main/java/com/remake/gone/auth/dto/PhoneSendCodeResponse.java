package com.remake.gone.auth.dto;

/**
 * 인증번호 발송 응답 DTO.
 *
 * @param expiresIn 인증번호 유효 시간(초)
 */
public record PhoneSendCodeResponse(long expiresIn) {
}
