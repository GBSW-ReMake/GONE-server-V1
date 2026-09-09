package com.remake.gone.outing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.repository.OutingRepository;
import com.remake.gone.outing.utils.OutingCodeGenerator;
import com.remake.gone.role.entity.Role;
import com.remake.gone.role.entity.UserRole;
import com.remake.gone.role.repository.RoleRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST/GET /api/v1/outings/{code}/locations}의 소유권/세부 역할 검증 통합 테스트(#97 코드
 * 리뷰 High 1번 대응).
 *
 * <p>{@code OutingDepartReturnOwnershipIntegrationTest}와 같은 이유로, {@code OutingServiceTest}의
 * Mockito 단위 테스트는 실제 필터 체인/DB를 거치지 않는다. 이 테스트는 학생 2명 + 외출증 +
 * (DISCIPLINE 케이스에서는) 실제 역할 레코드를 DB에 저장한 뒤, 실 서버 경로에서도
 * {@code validateOwnership}/{@code validateLocationAccess}가 의도한 대로 동작하는지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingLocationOwnershipIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private GbswRepository gbswRepository;

  @Autowired
  private OutingRepository outingRepository;

  @Autowired
  private RoleRepository roleRepository;

  @Autowired
  private UserRoleRepository userRoleRepository;

  private Outing outing;
  private User owner;
  private User otherStudent;
  private User teacher;
  private User unassignedTeacher;
  private User disciplineUser;
  private UserRole disciplineUserRole;

  @AfterEach
  void tearDown() {
    if (disciplineUserRole != null && disciplineUserRole.getId() != null) {
      userRoleRepository.deleteById(disciplineUserRole.getId());
    }
    if (outing != null && outing.getId() != null) {
      outingRepository.deleteById(outing.getId());
    }
    deleteStudent(owner);
    deleteStudent(otherStudent);
    deleteStudent(teacher);
    deleteStudent(unassignedTeacher);
    deleteStudent(disciplineUser);
  }

  private void deleteStudent(User user) {
    if (user != null && user.getId() != null) {
      userRepository.deleteById(user.getId());
      gbswRepository.deleteById(user.getGbsw().getId());
    }
  }

  private static int uniqueSuffix() {
    return ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
  }

  private User saveStudent() {
    int suffix = uniqueSuffix();
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.STUDENT)
        .name("학생" + suffix)
        .phoneNumber("010" + suffix)
        .grade(9)
        .classNo(9)
        .number(suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("stu" + suffix)
        .passwordHash("hash")
        .name("학생계정" + suffix)
        .phoneNumber("011" + suffix)
        .build());
  }

  private User saveTeacher() {
    int suffix = uniqueSuffix();
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.TEACHER)
        .name("선생님" + suffix)
        .phoneNumber("012" + suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("tch" + suffix)
        .passwordHash("hash")
        .name("선생님계정" + suffix)
        .phoneNumber("013" + suffix)
        .build());
  }

  private Outing saveOuting(User student, User teacherUser, OutingStatus status) {
    return outingRepository.save(Outing.builder()
        .code(OutingCodeGenerator.generate())
        .student(student)
        .teacher(teacherUser)
        .reason("위치/동선 IDOR 통합 테스트")
        .outingDate(LocalDate.now().plusDays(30))
        .timeSlot(OutingTimeSlot.CUSTOM)
        .startTime(LocalTime.of(9, 0))
        .endTime(LocalTime.of(10, 0))
        .status(status)
        .build());
  }

  @Test
  @DisplayName("본인 소유가 아닌 외출증에 위치 핑을 전송하면 403(OUTING_007)을 반환한다")
  void rejectsPingForNonOwner() throws Exception {
    owner = saveStudent();
    otherStudent = saveStudent();
    teacher = saveTeacher();
    outing = saveOuting(owner, teacher, OutingStatus.DEPARTED);
    String token = jwtProvider.createAccessToken(otherStudent.getId(), Set.of("STUDENT"));

    mockMvc.perform(post("/api/v1/outings/{code}/locations", outing.getCode())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"latitude\": 0.0, \"longitude\": 0.0}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OUTING_007"));
  }

  @Test
  @DisplayName("담당 아닌 TEACHER가 위치/동선을 조회하면 403(OUTING_007)을 반환한다")
  void rejectsGetForUnassignedTeacher() throws Exception {
    owner = saveStudent();
    teacher = saveTeacher();
    unassignedTeacher = saveTeacher();
    outing = saveOuting(owner, teacher, OutingStatus.DEPARTED);
    String token = jwtProvider.createAccessToken(unassignedTeacher.getId(), Set.of("TEACHER"));

    mockMvc.perform(get("/api/v1/outings/{code}/locations", outing.getCode())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OUTING_007"));
  }

  @Test
  @DisplayName("DISCIPLINE 역할이면 담당 아니어도 위치/동선을 조회할 수 있다")
  void allowsGetForDisciplineRole() throws Exception {
    owner = saveStudent();
    teacher = saveTeacher();
    disciplineUser = saveTeacher();
    outing = saveOuting(owner, teacher, OutingStatus.DEPARTED);
    Role disciplineRole = roleRepository.findByCode("DISCIPLINE")
        .orElseThrow(() -> new IllegalStateException("시드 데이터에 DISCIPLINE 역할이 없습니다."));
    disciplineUserRole = userRoleRepository.save(UserRole.builder()
        .user(disciplineUser)
        .role(disciplineRole)
        .build());
    String token = jwtProvider.createAccessToken(disciplineUser.getId(), Set.of("DISCIPLINE"));

    mockMvc.perform(get("/api/v1/outings/{code}/locations", outing.getCode())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.code").value(outing.getCode()));
  }
}
