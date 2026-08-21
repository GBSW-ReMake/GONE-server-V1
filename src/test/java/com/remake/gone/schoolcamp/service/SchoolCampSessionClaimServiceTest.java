package com.remake.gone.schoolcamp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.schoolcamp.entity.SchoolCampApplication;
import com.remake.gone.schoolcamp.repository.SchoolCampApplicationRepository;
import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
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
 * <p>실 DB에서만 검증 가능한 동시성 원자성은
 * {@code SchoolCampSessionClaimServiceIntegrationTest}(`@SpringBootTest`)가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class SchoolCampSessionClaimServiceTest {

  private static final Long SESSION_ID = 15L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 10, 9, 0);

  @Mock
  private SchoolCampSessionRepository sessionRepository;

  @Mock
  private SchoolCampApplicationRepository applicationRepository;

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
      verify(applicationRepository, never()).findBySessionIdAndCancelledAtIsNull(SESSION_ID);
    }

    @Test
    @DisplayName("기존 경로가 실패하고 활성 신청이 있으면 재점유하지 않고 false를 반환한다")
    void returnsFalseWhenActiveApplicationExists() {
      given(sessionRepository.claim(SESSION_ID, NOW)).willReturn(0);
      given(applicationRepository.findBySessionIdAndCancelledAtIsNull(SESSION_ID))
          .willReturn(Optional.of(SchoolCampApplication.builder().build()));

      boolean result = claimService.claim(SESSION_ID, NOW);

      assertThat(result).isFalse();
      verify(sessionRepository, never()).reclaimIfExpired(eq(SESSION_ID), any(), any());
    }

    @Test
    @DisplayName("기존 경로가 실패하고 활성 신청이 없으면 유예시간 기준으로 재점유를 시도한다")
    void attemptsReclaimWhenNoActiveApplication() {
      given(sessionRepository.claim(SESSION_ID, NOW)).willReturn(0);
      given(applicationRepository.findBySessionIdAndCancelledAtIsNull(SESSION_ID))
          .willReturn(Optional.empty());
      LocalDateTime expectedThreshold = NOW.minus(SchoolCampSessionClaimService.GRACE_PERIOD);
      given(sessionRepository.reclaimIfExpired(SESSION_ID, expectedThreshold, NOW))
          .willReturn(1);

      boolean result = claimService.claim(SESSION_ID, NOW);

      assertThat(result).isTrue();
      verify(sessionRepository).reclaimIfExpired(SESSION_ID, expectedThreshold, NOW);
    }

    @Test
    @DisplayName("재점유 시도가 실패하면(이미 누가 먼저 가져감) false를 반환한다")
    void returnsFalseWhenReclaimLosesRace() {
      given(sessionRepository.claim(SESSION_ID, NOW)).willReturn(0);
      given(applicationRepository.findBySessionIdAndCancelledAtIsNull(SESSION_ID))
          .willReturn(Optional.empty());
      given(sessionRepository.reclaimIfExpired(eq(SESSION_ID), any(), eq(NOW)))
          .willReturn(0);

      boolean result = claimService.claim(SESSION_ID, NOW);

      assertThat(result).isFalse();
    }
  }
}
