package com.remake.gone.user.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.user.dto.MyProfileResponse;
import com.remake.gone.user.dto.UpdateNameRequest;
import com.remake.gone.user.dto.UserSearchResponse;
import com.remake.gone.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 정보 조회/검색 관련 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
public class UserController {

  private final UserService userService;

  /**
   * 본인의 프로필 정보를 조회합니다. Access Token 인증이 필요합니다({@code SecurityConfig} 참고).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 현재 닉네임과 프로필 사진 설정 여부
   */
  @GetMapping("/me")
  public ApiResponse<MyProfileResponse> getMyProfile(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    MyProfileResponse response = userService.getMyProfile(principal.userId());
    return ApiResponse.success(response, "프로필 조회에 성공했습니다.");
  }

  /**
   * 본인의 별명을 변경합니다. Access Token 인증이 필요합니다({@code SecurityConfig} 참고).
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param request   새로 설정할 별명
   * @return 성공 여부만 담은 응답
   */
  @PatchMapping("/me/name")
  public ApiResponse<Void> changeName(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody UpdateNameRequest request
  ) {
    userService.changeName(principal.userId(), request.name());
    return ApiResponse.success(null, "별명이 변경되었습니다.");
  }

  /**
   * 실명이 검색어에 부분 일치하는 가입된 사용자를 검색합니다. Access Token 인증이 필요합니다
   * ({@code SecurityConfig} 참고).
   *
   * @param query 검색어(실명 부분 일치)
   * @return 검색 결과 목록
   */
  @GetMapping("/search")
  public ApiResponse<List<UserSearchResponse>> search(
      @RequestParam @NotBlank String query
  ) {
    List<UserSearchResponse> results = userService.search(query);
    return ApiResponse.success(results, "검색 결과입니다.");
  }
}
