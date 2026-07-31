package com.remake.gone.auth.dto;

/**
 * 인증번호 확인 응답 DTO.
 *
 * @param ticket    회원가입 진행에 사용할 티켓
 * @param expiresIn 티켓 유효 시간(초)
 */
public record PhoneVerifyCodeResponse(String ticket, long expiresIn) {
}
