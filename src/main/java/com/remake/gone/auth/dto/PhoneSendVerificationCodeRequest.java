package com.remake.gone.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 인증번호 발송 요청 DTO.
 *
 * @param phoneNumber 인증번호를 받을 휴대폰 번호
 */
public record PhoneSendCodeRequest(
    @NotBlank
    @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$", message = "올바른 휴대폰 번호 형식이 아닙니다")
    String phoneNumber) {
}
