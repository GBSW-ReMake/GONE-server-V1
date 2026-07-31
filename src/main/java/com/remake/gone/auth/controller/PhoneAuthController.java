package com.remake.gone.auth;

import com.remake.gone.auth.dto.PhoneSendCodeRequest;
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
@RequestMapping("/api/v1/auth/phone-verifications")
@RequiredArgsConstructor
public class PhoneAuthController {

  private final PhoneAuthService phoneAuthService;

  /**
   * 휴대폰 인증번호 발송을 요청합니다.
   *
   * @param request 인증번호를 받을 휴대폰 번호
   * @return 처리 결과
   */
  @PostMapping
  public ResponseEntity<ApiResponse<Void>> sendVerificationCode(
      @Valid @RequestBody PhoneSendCodeRequest request) {

    phoneAuthService.sendVerificationCode(request);

    return ResponseEntity.ok(ApiResponse.success(null, "인증번호가 발송되었습니다."));
  }
}
