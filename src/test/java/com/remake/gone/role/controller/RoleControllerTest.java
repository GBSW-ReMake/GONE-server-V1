package com.remake.gone.role.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.role.entity.Role;
import com.remake.gone.role.entity.UserRole;
import com.remake.gone.role.repository.RoleRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link RoleController}의 실제 DB·보안 필터 통합 테스트(#158).
 *
 * <p>실제 사용자·역할 데이터를 저장한 뒤 HTTP 요청을 보내, 인증 여부와 역할 유무에 따른 응답을
 * 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Autowired
  private GbswRepository gbswRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private UserRoleRepository userRoleRepository;

  @Autowired
  private RoleRepository roleRepository;

  private User userWithRole;
  private User userWithoutRole;

  @AfterEach
  void tearDown() {
    if (userWithRole != null) {
      userRoleRepository.deleteAll(
          userRoleRepository.findAll().stream()
              .filter(ur -> ur.getUser().getId().equals(userWithRole.getId()))
              .toList()
      );
      userRepository.delete(userWithRole);
      gbswRepository.delete(userWithRole.getGbsw());
    }
    if (userWithoutRole != null) {
      userRepository.delete(userWithoutRole);
      gbswRepository.delete(userWithoutRole.getGbsw());
    }
  }

  private User createUser(String phoneSuffix) {
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.STUDENT)
        .name("테스트유저" + phoneSuffix)
        .phoneNumber("0100000" + phoneSuffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("roletest" + phoneSuffix)
        .passwordHash("dummy-hash")
        .name("역할테스트" + phoneSuffix + ThreadLocalRandom.current().nextInt(10000))
        .phoneNumber("0100000" + phoneSuffix)
        .build());
  }

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/roles"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("역할이 없는 사용자는 빈 목록을 반환한다")
  void returnsEmptyListWhenNoRoles() throws Exception {
    userWithoutRole = createUser("1111");
    String token = jwtProvider.createAccessToken(userWithoutRole.getId(), Set.of());

    mockMvc.perform(get("/api/v1/users/me/roles")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.roles").isArray())
        .andExpect(jsonPath("$.data.roles").isEmpty());
  }

  @Test
  @DisplayName("역할이 있는 사용자는 코드와 한글명을 담아 반환한다")
  void returnsRolesWhenPresent() throws Exception {
    userWithRole = createUser("2222");
    Role discipline = roleRepository.findByCode("DISCIPLINE").orElseThrow();
    userRoleRepository.save(UserRole.builder().user(userWithRole).role(discipline).build());
    String token = jwtProvider.createAccessToken(userWithRole.getId(), Set.of("DISCIPLINE"));

    mockMvc.perform(get("/api/v1/users/me/roles")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.roles[0].code").value("DISCIPLINE"))
        .andExpect(jsonPath("$.data.roles[0].name").value("선도부"));
  }
}