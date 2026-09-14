package com.remake.gone.role.service;

import com.remake.gone.role.dto.UserRolesResponse;
import com.remake.gone.role.dto.UserRolesResponse.RoleInfo;
import com.remake.gone.role.entity.Role;
import com.remake.gone.role.repository.UserRoleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 역할(Role) 조회 관련 서비스.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

  private final UserRoleRepository userRoleRepository;

  /**
   * 특정 사용자가 가진 역할 목록을 조회합니다. 역할이 하나도 없어도 정상 케이스로 취급하며,
   * 이 경우 빈 리스트를 담아 반환합니다.
   *
   * @param userId 조회할 사용자 ID
   * @return 역할 코드·한글명 목록을 담은 응답
   */
  @Transactional(readOnly = true)
  public UserRolesResponse getMyRoles(Long userId) {
    List<Role> roles = userRoleRepository.findRolesByUserId(userId);
    List<RoleInfo> roleInfos = roles.stream()
        .map(role -> new RoleInfo(role.getCode(), role.getName()))
        .toList();
    return new UserRolesResponse(roleInfos);
  }
}