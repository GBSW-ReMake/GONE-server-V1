package com.remake.gone.outing.controller;

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
 * {@code POST /api/v1/outings/{code}/depart}, {@code /return}의 소유권 검증(IDOR 방지) 통합
 * 테스트(#43 코드 리뷰 Medium 1번 대응).
 *
 * <p>{@code OutingServiceTest}의 {@code rejectsWhenNotOwner}는 순수 Mockito 단위 테스트라 실제
 * 필터 체인/DB를 거치지 않는다. 이 테스트는 학생 2명 + 외출증을 실제로 DB에 저장한 뒤, STUDENT
 * 역할이지만 그 외출증의 소유자가 아닌 학생의 토큰으로 출발/도착을 시도했을 때 실 서버 경로에서도
 * 403({@code OUTING_007})이 반환되는지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingDepartReturnOwnershipIntegrationTest {

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

  private Outing outing;
  private User owner;
  private User otherStudent;
  private User teacher;

  @AfterEach
  void tearDown() {
    if (outing != null && outing.getId() != null) {
      outingRepository.deleteById(outing.getId());
    }
    deleteStudent(owner);
    deleteStudent(otherStudent);
    deleteStudent(teacher);
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
        .reason("IDOR 통합 테스트")
        .outingDate(LocalDate.now().plusDays(30))
        .timeSlot(OutingTimeSlot.CUSTOM)
        .startTime(LocalTime.of(9, 0))
        .endTime(LocalTime.of(10, 0))
        .status(status)
        .build());
  }

  @Test
  @DisplayName("본인 소유가 아닌 외출증에 출발 보고를 시도하면 403(OUTING_007)을 반환한다")
  void rejectsDepartForNonOwner() throws Exception {
    owner = saveStudent();
    otherStudent = saveStudent();
    teacher = saveTeacher();
    outing = saveOuting(owner, teacher, OutingStatus.APPROVED);
    String token = jwtProvider.createAccessToken(otherStudent.getId(), Set.of("STUDENT"));

    mockMvc.perform(post("/api/v1/outings/{code}/depart", outing.getCode())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"latitude\": 0.0, \"longitude\": 0.0}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OUTING_007"));
  }

  @Test
  @DisplayName("본인 소유가 아닌 외출증에 도착 보고를 시도하면 403(OUTING_007)을 반환한다")
  void rejectsReturnForNonOwner() throws Exception {
    owner = saveStudent();
    otherStudent = saveStudent();
    teacher = saveTeacher();
    outing = saveOuting(owner, teacher, OutingStatus.DEPARTED);
    String token = jwtProvider.createAccessToken(otherStudent.getId(), Set.of("STUDENT"));

    mockMvc.perform(post("/api/v1/outings/{code}/return", outing.getCode())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"latitude\": 0.0, \"longitude\": 0.0}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OUTING_007"));
  }
}
