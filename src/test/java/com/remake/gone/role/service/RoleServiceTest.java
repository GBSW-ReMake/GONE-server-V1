package com.remake.gone.role.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.remake.gone.role.dto.UserRolesResponse;
import com.remake.gone.role.entity.Role;
import com.remake.gone.role.repository.UserRoleRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RoleService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

  @Mock
  private UserRoleRepository userRoleRepository;

  private static final Long USER_ID = 1L;

  @Nested
  @DisplayName("getMyRoles")
  class GetMyRoles {

    @Test
    @DisplayName("역할이 있으면 코드와 한글명을 담아 반환한다")
    void returnsRolesWhenPresent() {
      Role discipline = Role.builder().id(1L).code("DISCIPLINE").name("선도부").build();
      Role student = Role.builder().id(2L).code("STUDENT").name("학생").build();
      given(userRoleRepository.findRolesByUserId(USER_ID))
          .willReturn(List.of(student, discipline));
      RoleService service = new RoleService(userRoleRepository);

      UserRolesResponse response = service.getMyRoles(USER_ID);

      assertThat(response.roles()).containsExactly(
          new UserRolesResponse.RoleInfo("STUDENT", "학생"),
          new UserRolesResponse.RoleInfo("DISCIPLINE", "선도부")
      );
    }

    @Test
    @DisplayName("역할이 하나도 없으면 빈 리스트를 반환한다")
    void returnsEmptyListWhenAbsent() {
      given(userRoleRepository.findRolesByUserId(USER_ID)).willReturn(List.of());
      RoleService service = new RoleService(userRoleRepository);

      UserRolesResponse response = service.getMyRoles(USER_ID);

      assertThat(response.roles()).isEmpty();
    }
  }
}