package com.remake.gone.auth.controller;

import com.remake.gone.auth.dto.PhoneSendCodeRequest;
import com.remake.gone.auth.dto.PhoneSendCodeResponse;
import com.remake.gone.auth.dto.PhoneVerifyCodeRequest;
import com.remake.gone.auth.dto.PhoneVerifyCodeResponse;
import com.remake.gone.auth.service.PhoneAuthService;
import com.remake.gone.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 휴대폰 인증 관련 API.
 */
@RestController
@RequestMapping("/api/v1/auth/phone")
@RequiredArgsConstructor
public class PhoneAuthController {

  private final PhoneAuthService phoneAuthService;

  /**
   * 휴대폰 인증번호 발송을 요청합니다.
   *
   * @param request 인증번호를 받을 휴대폰 번호
   * @return 발송된 인증번호의 유효 시간
   */
  @PostMapping("/send-code")
  public ResponseEntity<ApiResponse<PhoneSendCodeResponse>> sendVerificationCode(
      @Valid @RequestBody PhoneSendCodeRequest request) {

    PhoneSendCodeResponse response = phoneAuthService.sendVerificationCode(request);

    return ResponseEntity.ok(ApiResponse.success(response, "인증번호가 발송되었습니다."));
  }

  /**
   * 발송된 휴대폰 인증번호를 확인합니다.
   *
   * <p>확인에 성공하면 회원가입 진행에 사용할 signUpTicket을 발급합니다.
   *
   * @param request 확인할 휴대폰 번호와 사용자가 입력한 인증번호
   * @return 발급된 signUpTicket과 유효 시간
   */
  @PostMapping("/verify-code")
  public ResponseEntity<ApiResponse<PhoneVerifyCodeResponse>> verifyCode(
      @Valid @RequestBody PhoneVerifyCodeRequest request
  ) {

    PhoneVerifyCodeResponse response = phoneAuthService.verifyCode(request);

    return ResponseEntity.ok(ApiResponse.success(response, "인증번호가 확인되었습니다."));
  }
}
