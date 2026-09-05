package com.remake.gone.outing.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code OutingRepository.findByIdForUpdate}/{@code findByCodeForUpdate}가 실제로 같은
 * 외출증 행에 대한 동시 조회를 직렬화하는지 검증하는 통합 테스트(#99 CodeRabbit 지적 E).
 *
 * <p>{@code checkAndNotifyTimeout()}과 {@code returnOuting()}을 직접 동시에 호출하는
 * 방식으로는 두 트랜잭션이 "정확히 겹치는" 순간을 결정론적으로 재현할 수 없다(스레드
 * 스케줄링에 의존적이라 재현 여부가 매번 달라짐). 대신 이 테스트는 그 두 메서드가 공통으로
 * 의존하는 잠금 메커니즘 자체를 리포지토리 레벨에서 직접 검증한다 — 한 트랜잭션이 비관적
 * 쓰기 락을 잡고 있는 동안 다른 트랜잭션의 같은 행 조회가 실제로 블록되다가, 앞 트랜잭션이
 * 커밋된 뒤에야 진행되는지를 이벤트 순서로 확인한다. 이 보장만 있으면 서비스 레벨에서
 * {@code checkAndNotifyTimeout()}과 {@code returnOuting()} 중 어느 쪽이 먼저 락을 잡든
 * 나머지 하나는 앞선 트랜잭션이 완전히 끝난 뒤의 최신 상태를 보고 진행하게 된다.
 */
@SpringBootTest
class OutingTimeoutReturnRaceIntegrationTest {

  @Autowired
  private OutingRepository outingRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private GbswRepository gbswRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private Outing outing;
  private User student;
  private User teacher;

  @AfterEach
  void tearDown() {
    if (outing != null && outing.getId() != null) {
      outingRepository.deleteById(outing.getId());
    }
    deleteUser(student);
    deleteUser(teacher);
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

  private User saveUser(GbswType type) {
    int suffix = uniqueSuffix();
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(type)
        .name("계정" + suffix)
        .phoneNumber("010" + suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("u" + suffix)
        .passwordHash("hash")
        .name("이름" + suffix)
        .phoneNumber("011" + suffix)
        .build());
  }

  @Test
  @DisplayName("findByIdForUpdate로 락을 잡은 트랜잭션이 커밋할 때까지 "
      + "findByCodeForUpdate가 같은 행에 대해 블록된다")
  void secondTransactionWaitsForFirstToCommit() throws Exception {
    student = saveUser(GbswType.STUDENT);
    teacher = saveUser(GbswType.TEACHER);
    outing = outingRepository.save(Outing.builder()
        .code(OutingCodeGenerator.generate())
        .student(student)
        .teacher(teacher)
        .reason("타임아웃/복귀 경합 통합 테스트")
        .outingDate(LocalDate.now().plusDays(30))
        .timeSlot(OutingTimeSlot.CUSTOM)
        .startTime(LocalTime.of(9, 0))
        .endTime(LocalTime.of(10, 0))
        .status(OutingStatus.DEPARTED)
        .build());

    List<String> events = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch aboutToQuery = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    // 스레드 A: checkAndNotifyTimeout()이 하는 것처럼 findByIdForUpdate로 락을 잡은 채
    // 트랜잭션을 열어둔다(returnOuting()이 아직 커밋 전인 상황을 흉내낸다고 봐도 동일 —
    // 두 메서드 모두 같은 락 메커니즘을 쓰므로 어느 쪽이 먼저든 검증 대상은 같다).
    Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
      outingRepository.findByIdForUpdate(outing.getId());
      events.add("A-locked");
      lockAcquired.countDown();
      try {
        releaseLock.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));

    lockAcquired.await(5, TimeUnit.SECONDS);
    // 스레드 B: returnOuting()이 하는 것처럼 findByCodeForUpdate로 같은 행을 조회한다 —
    // A가 커밋하기 전까지는 DB가 이 SELECT ... FOR UPDATE를 블록해야 한다. aboutToQuery는
    // 쿼리 호출 "직전"에 내려, 이 스레드가 실제로 요청을 보내기 시작했다는 걸 메인
    // 스레드가 확인할 수 있게 한다 — CI 부하로 이 스레드 자체가 늦게 스케줄되는 경우와,
    // "요청은 보냈지만 아직 실행 중"인 경우를 구분하기 위함이다(CodeRabbit 지적: 고정
    // sleep만으로는 이 스레드가 아예 시작도 못 한 상태에서 통과할 수 있었다).
    Future<?> waiter = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
      aboutToQuery.countDown();
      outingRepository.findByCodeForUpdate(outing.getCode());
      events.add("B-locked");
    }));

    aboutToQuery.await(5, TimeUnit.SECONDS);
    // 요청이 실제로 DB에 도달해 대기 상태로 들어갈 시간을 짧게 준 뒤, B가 "아직 끝나지
    // 않았다"는 것 자체를 단언한다 — 락이 걸리지 않으면 이 조회는 거의 즉시 끝나므로,
    // 이 단언이 이 테스트에서 락이 실제로 걸렸는지를 검증하는 핵심 지점이다.
    Thread.sleep(300);
    assertThat(waiter.isDone()).as("findByCodeForUpdate가 아직 락 대기 중이어야 한다").isFalse();
    events.add("checkpoint");
    releaseLock.countDown();

    try {
      holder.get(5, TimeUnit.SECONDS);
      waiter.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdown();
    }

    assertThat(events).containsExactly("A-locked", "checkpoint", "B-locked");
  }
}
