package com.remake.gone.outing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/outings}의 {@code status=MISSED} 필터가 "DB에 이미 MISSED로 반영된 행"과
 * "아직 DB는 PENDING이지만 마감이 지난 행"을 모두 포함하는지 검증하는 실 DB 기반 통합
 * 테스트(#98 코드 리뷰/QA에서 발견한 회귀 재발 방지).
 *
 * <p>{@code OutingServiceTest}의 관련 단위 테스트는 리포지토리를 모킹하므로 실제 JPQL의
 * {@code status = MISSED OR (status = PENDING AND 마감 지남)} 조건이 올바른지 검증하지
 * 못한다 — {@code statusEq}를 {@code PENDING}으로 좁혀버린 최초 구현이 이미 스케줄러가
 * MISSED로 반영한 행을 놓치는 회귀가 실제로 있었고(#98 실서버 QA에서 발견), 단위 테스트만으로는
 * 잡히지 않았다. 이 테스트는 스케줄러 타이밍에 의존하지 않도록 두 케이스를 직접 DB에 심어
 * 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutingMissedFilterIntegrationTest {

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

  private User student;
  private User teacher;
  private Outing alreadyMissedInDb;
  private Outing stillPendingButExpired;
  private Outing stillPendingNotExpired;

  @AfterEach
  void tearDown() {
    deleteOuting(alreadyMissedInDb);
    deleteOuting(stillPendingButExpired);
    deleteOuting(stillPendingNotExpired);
    deleteUser(student);
    deleteUser(teacher);
  }

  private void deleteOuting(Outing outing) {
    if (outing != null && outing.getId() != null) {
      outingRepository.deleteById(outing.getId());
    }
  }

  private void deleteUser(User user) {
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
        .phoneNumber("014" + suffix)
        .grade(9)
        .classNo(9)
        .number(suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("mstu" + suffix)
        .passwordHash("hash")
        .name("학생계정" + suffix)
        .phoneNumber("015" + suffix)
        .build());
  }

  private User saveTeacher() {
    int suffix = uniqueSuffix();
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.TEACHER)
        .name("선생님" + suffix)
        .phoneNumber("016" + suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("mtch" + suffix)
        .passwordHash("hash")
        .name("선생님계정" + suffix)
        .phoneNumber("017" + suffix)
        .build());
  }

  private Outing saveOuting(
      OutingStatus status, LocalDate outingDate, LocalTime startTime) {
    return outingRepository.save(Outing.builder()
        .code(OutingCodeGenerator.generate())
        .student(student)
        .teacher(teacher)
        .reason("MISSED 필터 통합 테스트")
        .outingDate(outingDate)
        .timeSlot(OutingTimeSlot.CUSTOM)
        .startTime(startTime)
        .endTime(startTime.plusHours(1))
        .status(status)
        .build());
  }

  @Test
  @DisplayName("status=MISSED는 DB가 이미 MISSED인 행과 마감 지난 PENDING을 모두 반환한다")
  void includesBothAlreadyMissedAndExpiredPending() throws Exception {
    student = saveStudent();
    teacher = saveTeacher();
    // 어제 날짜는 시각과 무관하게 무조건 마감이 지난 상태다 — 스케줄러 타이밍과 무관하게
    // 결정론적으로 재현하기 위해 오늘이 아니라 어제로 고정한다.
    LocalDate outingDate = LocalDate.now().minusDays(1);
    alreadyMissedInDb = saveOuting(OutingStatus.MISSED, outingDate, LocalTime.of(9, 0));
    stillPendingButExpired = saveOuting(OutingStatus.PENDING, outingDate, LocalTime.of(10, 0));
    String token = jwtProvider.createAccessToken(1L, Set.of("DISCIPLINE"));

    mockMvc.perform(get("/api/v1/outings")
            .param("date", outingDate.toString().replace("-", ""))
            .param("status", "MISSED")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[*].code").value(
            org.hamcrest.Matchers.containsInAnyOrder(
                alreadyMissedInDb.getCode(), stillPendingButExpired.getCode())));
  }

  @Test
  @DisplayName("status=MISSED는 아직 마감 전인 PENDING은 제외한다")
  void excludesNotYetExpiredPending() throws Exception {
    student = saveStudent();
    teacher = saveTeacher();
    // 오늘 날짜 + 하루 중 가장 늦은 시각이라, 테스트가 언제 실행되든 "아직 마감 전"이 보장된다.
    stillPendingNotExpired =
        saveOuting(OutingStatus.PENDING, LocalDate.now(), LocalTime.of(23, 59));
    String token = jwtProvider.createAccessToken(1L, Set.of("DISCIPLINE"));

    mockMvc.perform(get("/api/v1/outings")
            .param("status", "MISSED")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].code")
            .value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(stillPendingNotExpired.getCode()))));
  }
}
