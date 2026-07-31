package com.remake.gone.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 인증번호 확인 요청 DTO.
 *
 * @param phoneNumber 인증번호를 받은 휴대폰 번호
 * @param code        사용자가 입력한 인증번호
 */
public record PhoneVerifyCodeRequest (
  @NotBlank
  @Pattern(regexp = "^01[0-9]\\d{7,8}$", message = "휴대폰 번호는 하이픈 없이 01012345678 형식으로 입력해주세요")
  String phoneNumber,

  @NotBlank
  @Pattern(regexp = "^[0-9]{6}$", message = "인증번호는 숫자 6자리로 입력해주세요")
  String code
){}
