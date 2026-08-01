package com.remake.gone.auth.controller;

import com.remake.gone.auth.dto.SignUpRequest;
import com.remake.gone.auth.service.AuthService;
import com.remake.gone.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 인증(Auth) 관련 API 컨트롤러.
 */
@RequestMapping("/api/v1/auth/")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * 휴대폰 인증을 마친 사용자의 회원가입을 처리합니다.
   *
   * @param request 회원가입 요청 정보
   * @return 성공 여부만 담은 응답
   */
  @PostMapping("/signup")
  public ApiResponse<Void> signUp(
      @RequestBody SignUpRequest request
  ) {
    authService.signUp(request);
    return ApiResponse.success(null, "회원가입이 완료되었습니다.");
  }
}
