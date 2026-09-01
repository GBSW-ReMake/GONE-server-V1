package com.remake.gone.common.schedule.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.schedule.ScheduledTask;
import com.remake.gone.common.schedule.ScheduledTaskRepository;
import com.remake.gone.common.security.JwtProvider;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link ScheduledTaskController}의 HTTP·인증 통합 테스트(#126).
 *
 * <p>실제 Spring Security 필터 체인과 실 DB를 거쳐, 인증/ADMIN 권한 게이팅·요청 파라미터
 * 바인딩·예외 응답을 함께 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduledTaskControllerTest {

  private static final String TASK_TYPE = "QA_CONTROLLER_TEST";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Autowired
  private ScheduledTaskRepository scheduledTaskRepository;

  // 각 테스트가 save()로 만든 행의 id를 여기 기록해뒀다가 tearDown에서 지운다. 리포지토리의
  // deleteByTaskTypeAndReferenceId 같은 파생 delete 쿼리는 자체 트랜잭션이 없어 @AfterEach
  // 밖(트랜잭션 경계 밖)에서 직접 호출하면 실패한다 — 기본 CRUD 메서드(deleteById)는 자체
  // 트랜잭션을 가지므로 안전하다.
  private final List<Long> createdIds = new ArrayList<>();

  @AfterEach
  void tearDown() {
    createdIds.forEach(id -> {
      try {
        scheduledTaskRepository.deleteById(id);
      } catch (EmptyResultDataAccessException e) {
        // 테스트 안에서 이미 삭제됐다(예: delete 엔드포인트 자체를 검증하는 테스트).
      }
    });
    createdIds.clear();
  }

  @Test
  @DisplayName("ADMIN이 목록을 조회하면 200을 반환한다")
  void returns200ForAdminList() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(get("/api/v1/scheduled-tasks")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.page").value(0));
  }

  @Test
  @DisplayName("ADMIN이 통계를 조회하면 200을 반환한다")
  void returns200ForAdminStats() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(get("/api/v1/scheduled-tasks/stats")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.total").exists());
  }

  @Test
  @DisplayName("인증 없이 목록을 조회하면 401 COMMON_002를 반환한다")
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/scheduled-tasks"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_002"));
  }

  @Test
  @DisplayName("ADMIN이 아니면 목록 조회 시 403 COMMON_003을 반환한다")
  void returns403ForNonAdmin() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("STUDENT"));

    mockMvc.perform(get("/api/v1/scheduled-tasks")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_003"));
  }

  @Test
  @DisplayName("FAILED 작업을 재시도하면 200과 함께 PENDING으로 바뀐 상태를 반환한다")
  void returns200ForRetry() throws Exception {
    ScheduledTask task = new ScheduledTask(
        TASK_TYPE, 1L, LocalDateTime.now(), Duration.ofMinutes(1), null);
    // maxFailureCount(5)번째 실패에야 FAILED로 바뀐다.
    for (int i = 0; i < 5; i++) {
      task.markFailed(
          LocalDateTime.now(), "boom", 5, Duration.ofSeconds(30), Duration.ofMinutes(30));
    }
    Long id = scheduledTaskRepository.save(task).getId();
    createdIds.add(id);
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(post("/api/v1/scheduled-tasks/" + id + "/retry")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING"));
  }

  @Test
  @DisplayName("존재하지 않는 id를 재시도하면 404 SCHEDULE_002를 반환한다")
  void returns404ForRetryMissingTask() throws Exception {
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(post("/api/v1/scheduled-tasks/999999999/retry")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SCHEDULE_002"));
  }

  @Test
  @DisplayName("작업을 삭제하면 200을 반환하고 실제로 삭제된다")
  void returns200ForDelete() throws Exception {
    ScheduledTask task = new ScheduledTask(TASK_TYPE, 1L, LocalDateTime.now(), null, null);
    Long id = scheduledTaskRepository.save(task).getId();
    createdIds.add(id);
    String token = jwtProvider.createAccessToken(1L, Set.of("ADMIN"));

    mockMvc.perform(delete("/api/v1/scheduled-tasks/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    assertThat(scheduledTaskRepository.existsById(id)).isFalse();
  }
}
