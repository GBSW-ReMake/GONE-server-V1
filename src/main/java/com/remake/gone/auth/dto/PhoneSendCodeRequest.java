package com.remake.gone.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 인증번호 발송 요청 DTO.
 *
 * <p>휴대폰 번호는 하이픈 없이 {@code 01012345678} 형식만 허용합니다.
 *
 * @param phoneNumber 인증번호를 받을 휴대폰 번호
 */
public record PhoneSendCodeRequest(
    @NotBlank
    @Pattern(regexp = "^01[0-9]\\d{7,8}$", message = "휴대폰 번호는 하이픈 없이 01012345678 형식으로 입력해주세요")
    String phoneNumber) {
}
