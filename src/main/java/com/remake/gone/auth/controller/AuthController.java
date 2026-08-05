package com.remake.gone.auth.controller;

import com.remake.gone.auth.dto.LoginIdCheckResponse;
import com.remake.gone.auth.dto.LoginRequest;
import com.remake.gone.auth.dto.NameCheckResponse;
import com.remake.gone.auth.dto.ReissueRequest;
import com.remake.gone.auth.dto.SignUpRequest;
import com.remake.gone.auth.dto.TokenResponse;
import com.remake.gone.auth.service.AuthService;
import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.user.exception.UserErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
   * 휴대폰 인증을 마친 사용자의 회원가입을 처리합니다. 가입 직후 클라이언트가 별도 로그인 없이
   * 바로 이어서 이름/프로필 사진을 설정할 수 있도록 Access/Refresh Token을 함께 발급합니다.
   *
   * @param request 회원가입 요청 정보
   * @return 발급된 토큰 정보
   */
  @PostMapping("/signup")
  public ApiResponse<TokenResponse> signUp(
      @Valid @RequestBody SignUpRequest request
  ) {
    TokenResponse response = authService.signUp(request);
    return ApiResponse.success(response, "회원가입이 완료되었습니다.");
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

  /**
   * 별명 중복 여부를 확인합니다.
   *
   * @param name 확인할 별명
   * @return 사용 가능 여부
   */
  @GetMapping("/name/check")
  public ApiResponse<NameCheckResponse> checkName(
      @RequestParam
      @NotBlank
      @Size(max = 20, message = "별명은 20자 이하로 입력해주세요")
      String name
  ) {
    boolean available = authService.isNameAvailable(name);
    String message = available
        ? "사용 가능한 별명입니다."
        : UserErrorCode.NAME_ALREADY_EXISTS.getDefaultMessage();
    return ApiResponse.success(new NameCheckResponse(available), message);
  }

  /**
   * 로그인 ID/비밀번호를 검증하고 Access/Refresh Token을 발급합니다.
   *
   * @param request 로그인 요청 정보
   * @return 발급된 토큰 정보
   */
  @PostMapping("/login")
  public ApiResponse<TokenResponse> login(
      @Valid @RequestBody LoginRequest request
  ) {
    TokenResponse response = authService.login(request);
    return ApiResponse.success(response, "로그인에 성공했습니다.");
  }

  /**
   * Refresh Token으로 Access/Refresh Token을 재발급합니다.
   *
   * @param request 기존 Refresh Token
   * @return 재발급된 토큰 정보
   */
  @PostMapping("/reissue")
  public ApiResponse<TokenResponse> reissue(
      @Valid @RequestBody ReissueRequest request
  ) {
    TokenResponse response = authService.reissue(request);
    return ApiResponse.success(response, "토큰이 재발급되었습니다.");
  }

  /**
   * 로그아웃합니다. Access Token 인증이 필요합니다({@code SecurityConfig} 참고).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 성공 여부만 담은 응답
   */
  @PostMapping("/logout")
  public ApiResponse<Void> logout(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    authService.logout(principal.userId());
    return ApiResponse.success(null, "로그아웃되었습니다.");
  }
}
