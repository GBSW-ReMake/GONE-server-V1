package com.remake.gone.role.dto;

import java.util.List;

/**
 * 본인 역할 목록 조회 응답.
 *
 * @param roles 현재 사용자가 가진 역할 목록. 역할이 하나도 없으면 빈 리스트({@code []})
 */
public record UserRolesResponse(List<RoleInfo> roles) {

  /**
   * 역할 하나에 대한 정보.
   *
   * @param code 역할 코드 (예: {@code "DISCIPLINE"}). JWT {@code roles} 클레임과 동일한 값
   * @param name 화면 표시용 한글명 (예: "선도부")
   */
  public record RoleInfo(String code, String name) {
  }
}