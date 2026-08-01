package com.remake.gone.auth.controller;

import com.remake.gone.auth.dto.LoginIdCheckResponse;
import com.remake.gone.auth.dto.SignUpRequest;
import com.remake.gone.auth.service.AuthService;
import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.user.exception.UserErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증(Auth) 관련 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
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
      @Valid @RequestBody SignUpRequest request
  ) {
    authService.signUp(request);
    return ApiResponse.success(null, "회원가입이 완료되었습니다.");
  }

  /**
   * 로그인 ID 중복 여부를 확인합니다.
   *
   * @param loginId 확인할 로그인 ID
   * @return 사용 가능 여부
   */
  @GetMapping("/login-id/check")
  public ApiResponse<LoginIdCheckResponse> checkLoginId(
      @RequestParam
      @NotBlank
      @Pattern(
          regexp = "^[a-zA-Z0-9]{4,20}$",
          message = "로그인 ID는 영문, 숫자로만 4자 이상 20자 이하로 입력해주세요"
      )
      String loginId
  ) {
    boolean available = authService.isLoginIdAvailable(loginId);
    String message = available
        ? "사용 가능한 아이디입니다."
        : UserErrorCode.LOGIN_ID_ALREADY_EXISTS.getDefaultMessage();
    return ApiResponse.success(new LoginIdCheckResponse(available), message);
  }
}
