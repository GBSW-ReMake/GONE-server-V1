package com.remake.gone.schoolcamp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.remake.gone.schoolcamp.entity.SchoolCampSession;
import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link SchoolCampSessionClaimService}의 동시성 통합 테스트(#68).
 *
 * <p>{@code SchoolCampServiceTest}의 목(Mockito) 기반 단위 테스트는 claim/release 호출
 * 여부만 검증할 뿐, {@code WHERE taken_at IS NULL} 조건부 {@code UPDATE}가 실제
 * MySQL(InnoDB)에서 동시 요청 중 정확히 하나만 성공시키는지, {@code REQUIRES_NEW}가
 * 실제로 즉시 커밋되는지는 목으로 검증할 수 없다 — 그래서 실 DB에 붙는 이 통합 테스트가
 * 별도로 필요하다({@code docs/domain/schoolcamp/68-schoolcamp-application.md} "테스트" 절
 * 참고). {@code application.yml}에 설정된 로컬 MySQL(다른 {@code @SpringBootTest}와 동일한
 * DB, CI에서는 서비스 컨테이너)이 떠 있어야 실행된다.
 */
@SpringBootTest
class SchoolCampSessionClaimServiceIntegrationTest {

  @Autowired
  private SchoolCampSessionClaimService claimService;

  @Autowired
  private SchoolCampSessionRepository sessionRepository;

  private SchoolCampSession session;

  @AfterEach
  void tearDown() {
    if (session != null && session.getId() != null) {
      sessionRepository.deleteById(session.getId());
    }
  }

  /**
   * 세션의 {@code camp_date}는 UNIQUE라, 테스트끼리(그리고 같은 DB를 공유하는 다른 실행과)
   * 겹치지 않도록 매번 임의의 먼 미래 날짜를 쓴다. 요일 검증은 {@code SchoolCampService
   * .registerCampDates}에서만 하므로, 리포지토리에 직접 저장하는 이 테스트는 요일을 신경
   * 쓰지 않아도 된다.
   */
  private static SchoolCampSession newSession() {
    LocalDate campDate = LocalDate.now().plusYears(3)
        .plusDays(ThreadLocalRandom.current().nextInt(100_000));
    return SchoolCampSession.builder().campDate(campDate).build();
  }

  @Nested
  @DisplayName("claim")
  class Claim {

    @Test
    @DisplayName("같은 세션에 동시에 여러 번 호출해도 정확히 하나만 성공한다")
    void onlyOneClaimSucceedsUnderConcurrency() throws Exception {
      session = sessionRepository.save(newSession());
      int concurrentRequests = 20;
      ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
      CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
      CountDownLatch startLatch = new CountDownLatch(1);

      List<Callable<Boolean>> tasks = IntStream.range(0, concurrentRequests)
          .<Callable<Boolean>>mapToObj(i -> () -> {
            readyLatch.countDown();
            startLatch.await();
            return claimService.claim(session.getId(), LocalDateTime.now());
          })
          .toList();

      try {
        List<Future<Boolean>> futures = tasks.stream().map(executor::submit).toList();
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        long successCount = 0;
        for (Future<Boolean> future : futures) {
          if (future.get(10, TimeUnit.SECONDS)) {
            successCount++;
          }
        }

        assertThat(successCount).isEqualTo(1);
      } finally {
        executor.shutdown();
      }

      SchoolCampSession reloaded = sessionRepository.findById(session.getId()).orElseThrow();
      assertThat(reloaded.getTakenAt()).isNotNull();
    }

    @Test
    @DisplayName("REQUIRES_NEW로 즉시 커밋되어 호출 직후 별도 조회에서 바로 보인다")
    void commitsImmediately() {
      session = sessionRepository.save(newSession());

      boolean claimed = claimService.claim(session.getId(), LocalDateTime.now());

      assertThat(claimed).isTrue();
      // claimService.claim 호출이 끝난 이 시점에는 그 REQUIRES_NEW 트랜잭션이 이미 커밋된
      // 상태여야 한다 — 이 테스트 메서드 자체는 트랜잭션으로 감싸여 있지 않으므로, 아래
      // 재조회는 claim이 실행한 트랜잭션과 별개의 새 트랜잭션/영속성 컨텍스트에서 이뤄진다.
      SchoolCampSession reloaded = sessionRepository.findById(session.getId()).orElseThrow();
      assertThat(reloaded.getTakenAt()).isNotNull();
    }
  }

  @Nested
  @DisplayName("release")
  class Release {

    @Test
    @DisplayName("claim 이후 호출하면 taken_at이 다시 null로 돌아온다")
    void reopensSession() {
      session = sessionRepository.save(newSession());
      claimService.claim(session.getId(), LocalDateTime.now());

      claimService.release(session.getId());

      SchoolCampSession reloaded = sessionRepository.findById(session.getId()).orElseThrow();
      assertThat(reloaded.getTakenAt()).isNull();
    }
  }
}
