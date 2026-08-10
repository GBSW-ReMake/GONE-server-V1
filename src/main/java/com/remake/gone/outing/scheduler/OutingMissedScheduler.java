package com.remake.gone.outing.scheduler;

import com.remake.gone.outing.service.OutingService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 마감이 지난 {@code PENDING} 외출증을 DB에서 실제로 {@code MISSED}로 갱신하는 백그라운드
 * 스케줄러(#42). 이 프로젝트에서 처음 쓰는 {@code @Scheduled} 컴포넌트다.
 *
 * <p>조회(읽기 전용 트랜잭션)와 건별 갱신(각각 독립 트랜잭션)을 분리해서 호출한다 — 한
 * 배치 트랜잭션으로 묶으면, 승인/거절이 스케줄러의 읽기 이후 먼저 커밋된 건을 스케줄러가
 * 자신의 낡은 스냅샷으로 그대로 덮어써 승인/거절 결과가 유실될 수 있다(#42 코드 리뷰에서
 * 확인). {@link OutingService#markSingleOutingAsMissed(Long)}가 건마다 독립 트랜잭션에서
 * 그 순간 상태를 다시 확인하고 낙관적 락으로 충돌을 감지하므로, 이 컴포넌트는 트랜잭션
 * 경계를 직접 다루지 않고 두 서비스 메서드를 순서대로 호출하기만 한다.
 */
@Component
@RequiredArgsConstructor
public class OutingMissedScheduler {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final long FIXED_DELAY_MILLIS = 60_000L;

  private final OutingService outingService;

  /**
   * 이전 실행이 끝난 뒤 1분마다 실행됩니다. {@code fixedRate}가 아니라 {@code fixedDelay}를
   * 써서, 실행 시간이 늘어나도 다음 실행과 겹치지 않게 합니다.
   */
  @Scheduled(fixedDelay = FIXED_DELAY_MILLIS)
  public void markOverdueOutingsAsMissed() {
    LocalDateTime now = LocalDateTime.now(KST);
    List<Long> overdueIds =
        outingService.findOverdueOutingIds(now.toLocalDate(), now.toLocalTime());
    overdueIds.forEach(outingService::markSingleOutingAsMissed);
  }
}
