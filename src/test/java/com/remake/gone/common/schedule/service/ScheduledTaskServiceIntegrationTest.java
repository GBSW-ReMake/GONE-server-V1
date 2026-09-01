package com.remake.gone.common.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.remake.gone.common.schedule.entity.ScheduledTask;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.repository.ScheduledTaskRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link ScheduledTaskService}의 실 DB 기반 통합 테스트(#120 코드 리뷰 High 1번 대응).
 *
 * <p>{@link ScheduledTaskServiceTest}는 리포지토리를 mock하므로 Hibernate의 실제 flush
 * 순서(IDENTITY 전략의 즉시 INSERT와 지연된 DELETE 간 경합)를 검증하지 못한다. 이 테스트는
 * DONE 상태로 끝난 task를 실제로 삭제-후-재등록하는 경로가 유니크 제약(task_type,
 * reference_id) 위반 없이 성공하는지 실 DB로 확인한다.
 */
@SpringBootTest
class ScheduledTaskServiceIntegrationTest {

  private static final String TASK_TYPE = "QA_INTEGRATION_TEST";
  private static final Long REFERENCE_ID = -1L;

  @Autowired
  private ScheduledTaskService scheduledTaskService;

  @Autowired
  private ScheduledTaskRepository scheduledTaskRepository;

  @AfterEach
  void tearDown() {
    // 리포지토리의 delete 파생 쿼리는 자체 트랜잭션이 없어 테스트 메서드 밖(@AfterEach)에서
    // 직접 호출하면 실패한다 — @Transactional인 서비스 메서드를 통해 지운다.
    scheduledTaskService.cancel(TASK_TYPE, REFERENCE_ID);
  }

  @Test
  @DisplayName("DONE으로 끝난 task를 같은 (taskType, referenceId)로 재등록해도 유니크 제약 위반이 나지 않는다")
  void reregistersAfterDoneWithoutConstraintViolation() {
    LocalDateTime now = LocalDateTime.now();
    scheduledTaskService.schedule(TASK_TYPE, REFERENCE_ID, now, null, null);
    ScheduledTask first =
        scheduledTaskRepository.findByTaskTypeAndReferenceId(TASK_TYPE, REFERENCE_ID).orElseThrow();
    first.markDone(now);
    scheduledTaskRepository.saveAndFlush(first);

    assertThatCode(() -> scheduledTaskService.schedule(TASK_TYPE, REFERENCE_ID, now, null, null))
        .doesNotThrowAnyException();

    Optional<ScheduledTask> second =
        scheduledTaskRepository.findByTaskTypeAndReferenceId(TASK_TYPE, REFERENCE_ID);
    assertThat(second).isPresent();
    assertThat(second.get().getId()).isNotEqualTo(first.getId());
    assertThat(second.get().getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
  }
}
