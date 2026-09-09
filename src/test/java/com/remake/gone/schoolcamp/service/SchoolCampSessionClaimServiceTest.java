package com.remake.gone.schoolcamp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SchoolCampSessionClaimService}에 대한 단위 테스트(#84 유령 점유 재점유 로직 위주).
 *
 * <p>"활성 신청 없음"과 "유예시간 지남" 조건은 {@link SchoolCampSessionRepository
 * #reclaimIfExpired}의 원자적 SQL 안에서 같이 평가되므로(코드 리뷰 반영), 이 목(Mockito)
 * 기반 테스트로는 그 조건들 자체를 검증할 수 없다 — {@code reclaimIfExpired}가 반환하는
 * 영향받은 행 수를 이 서비스가 올바르게 위임/반환하는지만 검증한다. 실 DB에서만 검증
 * 가능한 SQL 조건과 동시성 원자성은 {@code SchoolCampSessionClaimServiceIntegrationTest}
 * (`@SpringBootTest`)가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class SchoolCampSessionClaimServiceTest {

  private static final Long SESSION_ID = 15L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 10, 9, 0);

  @Mock
  private SchoolCampSessionRepository sessionRepository;

  @InjectMocks
  private SchoolCampSessionClaimService claimService;

  @Nested
  @DisplayName("claim")
  class Claim {

    @Test
    @DisplayName("기존 경로가 성공하면 재점유를 시도하지 않는다")
    void returnsTrueImmediatelyWhenFastPathSucceeds() {
      given(sessionRepository.claim(SESSION_ID, NOW)).willReturn(1);

      boolean result = claimService.claim(SESSION_ID, NOW);

      assertThat(result).isTrue();
      verify(sessionRepository, never()).reclaimIfExpired(anyLong(), any(), any());
    }

    @Test
    @DisplayName("기존 경로가 실패하면 유예시간 기준으로 재점유를 시도하고 결과를 그대로 반환한다(성공)")
    void delegatesToReclaimWhenFastPathFailsAndSucceeds() {
      given(sessionRepository.claim(SESSION_ID, NOW)).willReturn(0);
      LocalDateTime expectedThreshold = NOW.minus(SchoolCampSessionClaimService.GRACE_PERIOD);
      given(sessionRepository.reclaimIfExpired(SESSION_ID, expectedThreshold, NOW))
          .willReturn(1);

      boolean result = claimService.claim(SESSION_ID, NOW);

      assertThat(result).isTrue();
      verify(sessionRepository).reclaimIfExpired(SESSION_ID, expectedThreshold, NOW);
    }

    @Test
    @DisplayName("기존 경로가 실패하고 재점유도 실패하면(유예시간 이내 또는 활성 신청 있음) false를 반환한다")
    void delegatesToReclaimWhenFastPathFailsAndReclaimFails() {
      given(sessionRepository.claim(SESSION_ID, NOW)).willReturn(0);
      LocalDateTime expectedThreshold = NOW.minus(SchoolCampSessionClaimService.GRACE_PERIOD);
      given(sessionRepository.reclaimIfExpired(SESSION_ID, expectedThreshold, NOW))
          .willReturn(0);

      boolean result = claimService.claim(SESSION_ID, NOW);

      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("release")
  class Release {

    @Test
    @DisplayName("호출한 쪽이 넘긴 expectedTakenAt을 그대로 리포지토리에 전달한다")
    void passesExpectedTakenAtThrough() {
      claimService.release(SESSION_ID, NOW);

      verify(sessionRepository).release(SESSION_ID, NOW);
    }
  }
}
