package com.remake.gone.role.controller;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.role.dto.UserRolesResponse;
import com.remake.gone.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 역할(Role) 조회 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class RoleController {

  private final RoleService roleService;

  /**
   * 본인이 가진 역할 목록을 조회합니다. Access Token 인증이 필요합니다({@code SecurityConfig}
   * 참고). 역할이 하나도 없는 계정도 정상 케이스이며, 이 경우 빈 목록을 200으로 반환합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @return 역할 코드·한글명 목록
   */
  @GetMapping("/roles")
  public ApiResponse<UserRolesResponse> getMyRoles(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    UserRolesResponse response = roleService.getMyRoles(principal.userId());
    return ApiResponse.success(response, "역할 목록 조회에 성공했습니다.");
  }
}