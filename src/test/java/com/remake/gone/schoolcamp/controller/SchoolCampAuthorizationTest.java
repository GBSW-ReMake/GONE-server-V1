package com.remake.gone.schoolcamp.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/school-camps}의 인가(@PreAuthorize) 통합 테스트.
 *
 * <p>{@code SchoolCampControllerTest}의 {@code @WebMvcTest(addFilters = false)} 슬라이스는
 * Spring Security 필터 체인이 MockMvc에 붙지 않아 인가 자체를 검증할 수 없다(관례는
 * {@code OutingApproveAuthorizationTest} 등 outing 도메인의 {@code *AuthorizationTest}
 * 참고). 이 테스트는 실제 필터 체인(+ {@code @EnableMethodSecurity})을 통해 STUDENT 역할로는
 * 이 엔드포인트에 접근할 수 없는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SchoolCampAuthorizationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Test
  @DisplayName("인증 없이 요청하면 401을 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(post("/api/v1/school-camps")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"campDates\": [\"20260406\"]}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("STUDENT 역할로 요청하면 403을 반환한다(@EnableMethodSecurity가 실제로 동작함)")
  void returns403ForStudentRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(post("/api/v1/school-camps")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"campDates\": [\"20260406\"]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("신청 엔드포인트는 인증 없이 요청하면 401을 반환한다")
  void applyReturns401WithoutToken() throws Exception {
    mockMvc.perform(post("/api/v1/school-camps/1/applications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"teacherUserId\": 42}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("신청 엔드포인트는 TEACHER 역할로 요청하면 403을 반환한다(STUDENT 전용)")
  void applyReturns403ForTeacherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("TEACHER"));

    mockMvc.perform(post("/api/v1/school-camps/1/applications")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"teacherUserId\": 42}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("취소 엔드포인트는 인증 없이 요청하면 401을 반환한다(#70)")
  void cancelReturns401WithoutToken() throws Exception {
    mockMvc.perform(delete("/api/v1/school-camps/applications/1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("수정 엔드포인트는 인증 없이 요청하면 401을 반환한다(#70)")
  void updateReturns401WithoutToken() throws Exception {
    mockMvc.perform(patch("/api/v1/school-camps/applications/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"teacherUserId\": 42}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("참여 내역 목록 엔드포인트는 인증 없이 요청하면 401을 반환한다(#69)")
  void myParticipationsReturns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/school-camps/me"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("참여 내역 목록 엔드포인트는 STUDENT/TEACHER 둘 다 아닌 역할로 요청하면 403을 반환한다(#69)")
  void myParticipationsReturns403ForOtherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(get("/api/v1/school-camps/me")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("참여 신청 상세 엔드포인트는 인증 없이 요청하면 401을 반환한다(#69)")
  void myParticipationDetailReturns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/school-camps/applications/1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("참여 신청 상세 엔드포인트는 STUDENT/TEACHER 둘 다 아닌 역할로 요청하면 403을 반환한다(#69)")
  void myParticipationDetailReturns403ForOtherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(get("/api/v1/school-camps/applications/1")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("대기 등록 엔드포인트는 인증 없이 요청하면 401을 반환한다(#83)")
  void registerWaitlistReturns401WithoutToken() throws Exception {
    mockMvc.perform(post("/api/v1/school-camps/waitlist"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("대기 등록 엔드포인트는 TEACHER 역할로 요청하면 403을 반환한다(STUDENT 전용, #83)")
  void registerWaitlistReturns403ForTeacherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("TEACHER"));

    mockMvc.perform(post("/api/v1/school-camps/waitlist")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("대기 취소 엔드포인트는 인증 없이 요청하면 401을 반환한다(#83)")
  void cancelWaitlistReturns401WithoutToken() throws Exception {
    mockMvc.perform(delete("/api/v1/school-camps/waitlist"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("대기 취소 엔드포인트는 TEACHER 역할로 요청하면 403을 반환한다(STUDENT 전용, #83)")
  void cancelWaitlistReturns403ForTeacherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("TEACHER"));

    mockMvc.perform(delete("/api/v1/school-camps/waitlist")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("대기 상태 조회 엔드포인트는 인증 없이 요청하면 401을 반환한다(#83)")
  void getWaitlistStatusReturns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/school-camps/waitlist/me"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("대기 상태 조회 엔드포인트는 TEACHER 역할로 요청하면 403을 반환한다(STUDENT 전용, #83)")
  void getWaitlistStatusReturns403ForTeacherRole() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("TEACHER"));

    mockMvc.perform(get("/api/v1/school-camps/waitlist/me")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }
}
