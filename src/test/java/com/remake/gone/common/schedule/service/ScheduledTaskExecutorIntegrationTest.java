package com.remake.gone.common.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.remake.gone.common.schedule.entity.ScheduledTask;
import com.remake.gone.common.schedule.enums.ScheduledTaskStatus;
import com.remake.gone.common.schedule.repository.ScheduledTaskRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScheduledTaskExecutor}의 실 트랜잭션 경계를 검증하는 통합 테스트(#99 코드 리뷰
 * 보류 항목 (a)/(b) 재현·검증, #120/#126 코드 리뷰에서 넘어온 것). {@link
 * ScheduledTaskExecutorTest}는 리포지토리를 mock하므로 Spring 트랜잭션 프록시의 실제 전파
 * 동작(REQUIRES_NEW 분리, rollback-only 전이)을 검증하지 못한다 — 이 테스트는 실 DB와 실
 * 트랜잭션 매니저로 그 동작 자체를 검증한다.
 *
 * <p>(b) 원자적 claim은 진짜 동시성 재현(별도 스레드로 claim/cancel을 정확히 겹치게 만드는
 * 것) 대신, "cancel이 먼저 커밋된 뒤 claim이 시도되면 실패한다"는 핵심 불변조건만
 * 결정론적으로 검증한다 — 진짜 스레드 경합 테스트는 타이밍에 의존해 플레이키해지고, 이
 * 보장 자체는 단일 원자적 {@code UPDATE ... WHERE status='PENDING'}(체크-앤드-셋)이라는
 * 잘 알려진 패턴에 기반하므로 반복 실행 없이도 신뢰할 수 있다.
 */
@SpringBootTest
class ScheduledTaskExecutorIntegrationTest {

  private static final String THROWING_TASK_TYPE = "QA_INTEGRATION_THROWING";
  private static final Long REFERENCE_ID = -2L;

  @Autowired
  private ScheduledTaskService scheduledTaskService;

  @Autowired
  private ScheduledTaskRepository scheduledTaskRepository;

  @Autowired
  private ScheduledTaskExecutor scheduledTaskExecutor;

  @Autowired
  private ThrowingHandlerProbe throwingHandlerProbe;

  @AfterEach
  void tearDown() {
    scheduledTaskService.cancel(THROWING_TASK_TYPE, REFERENCE_ID);
    throwingHandlerProbe.reset();
  }

  @Test
  @DisplayName("(a) handler 내부의 @Transactional(REQUIRED) 협력자가 예외를 던져도 "
      + "markFailed 기록이 유실되지 않는다")
  void recordsFailureEvenWhenHandlerParticipatesInFailingTransaction() {
    LocalDateTime now = LocalDateTime.now();
    // scheduledAt을 내일로 잡아 nextAttemptAt도 미래가 되게 한다 — 이 앱은 테스트에서도
    // @EnableScheduling이 그대로 켜져 있어(#42) 실제 ScheduledTaskRunner가 10초마다
    // 폴링한다. scheduledAt을 "지금"으로 두면 등록 직후 배경 폴러가 이 task를 먼저 집어가
    // 아래 수동 execute() 호출과 경합할 수 있어, due 조건에 아예 걸리지 않게 미룬다.
    scheduledTaskService.schedule(
        THROWING_TASK_TYPE, REFERENCE_ID, now.plusDays(1), Duration.ofMinutes(1), null);
    Long taskId = scheduledTaskRepository
        .findByTaskTypeAndReferenceId(THROWING_TASK_TYPE, REFERENCE_ID).orElseThrow().getId();

    // 수정 전 코드였다면 execute()가 통째로 REQUIRES_NEW 트랜잭션이라, 아래 handler가
    // 참여 트랜잭션에서 던진 예외가 그 트랜잭션 전체를 rollback-only로 표시해 이 호출
    // 자체가 UnexpectedRollbackException을 던지며 markFailed 기록이 유실됐다.
    scheduledTaskExecutor.execute(taskId, now);

    ScheduledTask reloaded = scheduledTaskRepository.findById(taskId).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
    assertThat(reloaded.getFailureCount()).isEqualTo(1);
    assertThat(reloaded.getLastError()).isNotBlank();
    assertThat(throwingHandlerProbe.invocationCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("(b) claim 시도 전 cancel이 먼저 커밋되면 claim이 실패해 handler.handle()이 "
      + "호출되지 않는다")
  void doesNotInvokeHandlerWhenCancelledBeforeClaim() {
    LocalDateTime now = LocalDateTime.now();
    // 배경 폴러와의 경합을 피하는 이유는 위 (a) 테스트의 주석 참고.
    scheduledTaskService.schedule(
        THROWING_TASK_TYPE, REFERENCE_ID, now.plusDays(1), Duration.ofMinutes(1), null);
    Long taskId = scheduledTaskRepository
        .findByTaskTypeAndReferenceId(THROWING_TASK_TYPE, REFERENCE_ID).orElseThrow().getId();
    // returnOuting()이 같은 트랜잭션에서 커밋하는 cancel() 호출을 재현 — 이 시점 이후
    // claim이 이 task의 status=PENDING 조건에 걸릴 행을 더 이상 찾지 못해야 한다.
    scheduledTaskService.cancel(THROWING_TASK_TYPE, REFERENCE_ID);

    scheduledTaskExecutor.execute(taskId, now);

    assertThat(throwingHandlerProbe.invocationCount()).isZero();
    Optional<ScheduledTask> remaining = scheduledTaskRepository.findById(taskId);
    assertThat(remaining).isEmpty();
  }

  @TestConfiguration
  static class Config {

    @Bean(THROWING_TASK_TYPE)
    ScheduledTaskHandler throwingHandler(
        ThrowingHandlerProbe probe, FailingCollaborator collaborator) {
      return referenceId -> {
        probe.increment();
        // handler.handle() 안에서 호출하는, 자신만의 @Transactional(REQUIRED) 경계를 가진
        // 도메인 서비스 호출을 재현한다(OutingService.checkAndNotifyTimeout이 그 예).
        collaborator.doWorkThatFails();
        return true; // 도달하지 않음 — doWorkThatFails()가 항상 예외를 던진다.
      };
    }

    @Bean
    ThrowingHandlerProbe throwingHandlerProbe() {
      return new ThrowingHandlerProbe();
    }

    @Bean
    FailingCollaborator failingCollaborator() {
      return new FailingCollaborator();
    }
  }

  static class ThrowingHandlerProbe {
    private final AtomicInteger count = new AtomicInteger();

    void increment() {
      count.incrementAndGet();
    }

    int invocationCount() {
      return count.get();
    }

    void reset() {
      count.set(0);
    }
  }

  /** handler.handle() 안에서 호출되는, 자체 트랜잭션 경계를 가진 협력자(테스트 전용). */
  static class FailingCollaborator {

    @Transactional
    void doWorkThatFails() {
      throw new IllegalStateException("의도된 실패 — 참여 트랜잭션 rollback-only 재현용");
    }
  }
}
