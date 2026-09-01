package com.remake.gone.common.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 범용 이벤트 스케줄 엔티티(#120). {@code common/schedule} 패키지가 도메인 무관하게 다루는
 * 예약 상태로, {@code task_type} 컬럼으로 도메인을 구분한다.
 *
 * <p>{@code db/migration/V20260901104142__add_scheduled_task.sql}의 {@code scheduled_task}
 * 테이블에 대응한다.
 */
@Entity
@Table(name = "scheduled_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduledTask {

  private static final int MAX_ERROR_LENGTH = 500;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_type", nullable = false, length = 50)
  private String taskType;

  @Column(name = "reference_id", nullable = false)
  private Long referenceId;

  @Column(name = "scheduled_at", nullable = false)
  private LocalDateTime scheduledAt;

  @Column(name = "interval_seconds")
  private Integer intervalSeconds;

  @Column(name = "end_at")
  private LocalDateTime endAt;

  @Column(name = "next_attempt_at", nullable = false)
  private LocalDateTime nextAttemptAt;

  @Column(name = "last_executed_at")
  private LocalDateTime lastExecutedAt;

  @Column(name = "last_attempted_at")
  private LocalDateTime lastAttemptedAt;

  @Column(name = "failure_count", nullable = false)
  private int failureCount;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ScheduledTaskStatus status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 새 예약을 만든다.
   *
   * @param taskType    도메인을 구분하는 식별자(예: {@code "OUTING_TIMEOUT"}).
   * @param referenceId 도메인 엔티티의 PK.
   * @param scheduledAt 최초 실행 예정 시각.
   * @param interval    성공 실행 후 재실행 간격. {@code null}이면 1회성 작업이다.
   * @param cap         발송 상한(scheduledAt 기준 상대값). {@code null}이면 상한이 없다.
   */
  public ScheduledTask(String taskType, Long referenceId, LocalDateTime scheduledAt,
      Duration interval, Duration cap) {
    this.taskType = taskType;
    this.referenceId = referenceId;
    this.scheduledAt = scheduledAt;
    // interval이 null이면 1회성 작업이다 — isOneShot()이 이 값을 기준으로 판단한다.
    this.intervalSeconds = toIntervalSeconds(interval);
    // cap은 "등록 시점부터 며칠/몇 시간"이 아니라 scheduledAt 기준 상대값이다.
    // 예: departOuting에서 cap=3시간이면 "종료 시각(scheduledAt)으로부터 3시간까지만 재발송".
    this.endAt = cap == null ? null : scheduledAt.plus(cap);
    // Runner의 폴링 조회(findDueTaskIds)는 nextAttemptAt만 본다 — 최초 등록 시에는
    // scheduledAt과 같은 값으로 시작해, 등록 즉시 "언제 처음 확인할지"를 폴링이 알 수 있게 한다.
    this.nextAttemptAt = scheduledAt;
    this.failureCount = 0;
    this.status = ScheduledTaskStatus.PENDING;
  }

  /**
   * {@code interval}을 초 단위 정수로 좁힌다. {@code Duration.getSeconds()}는 1초 미만
   * 나머지를 잘라버려서(예: 500ms → 0초) {@link #markSucceeded}가 즉시 다음 시도를 예약하는
   * 결과를 낳을 수 있고, 초 단위 값이 {@code int} 범위를 넘으면 캐스팅 시 조용히 값이
   * 깨진다 — 둘 다 호출 시점에 막는다.
   */
  private static Integer toIntervalSeconds(Duration interval) {
    if (interval == null) {
      return null;
    }
    if (interval.isNegative() || interval.isZero() || interval.getNano() != 0) {
      throw new IllegalArgumentException(
          "interval은 1초 이상의 정수초 단위 Duration이어야 합니다: " + interval);
    }
    if (interval.getSeconds() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("interval이 int 범위를 초과합니다: " + interval);
    }
    return (int) interval.getSeconds();
  }

  /** interval_seconds가 없으면(1회성 작업) 핸들러 반환값과 무관하게 한 번만 실행한다. */
  public boolean isOneShot() {
    return intervalSeconds == null;
  }

  /** end_at을 넘겼으면 핸들러가 아직 "끝났다"고 하지 않아도 더 이상 재시도하지 않는다. */
  public boolean isPastCap(LocalDateTime now) {
    return endAt != null && now.isAfter(endAt);
  }

  /**
   * 더 이상 재실행하지 않도록 상태를 종료 처리한다. {@link #markSucceeded}와 동일하게
   * 시도/실행 시각을 남기고 실패 이력을 지운다 — 그렇지 않으면 실패를 몇 번 거친 뒤 성공해
   * DONE 처리된 task가 {@code last_attempted_at}/{@code last_executed_at}이 여전히
   * {@code null}이거나 {@code failure_count}/{@code last_error}가 남아있는 채로 보여,
   * 이 테이블만 보고 상태를 판단하려는 모니터링 취지(기획서 "향후 모니터링과의 관계" 절)와
   * 어긋난다.
   */
  public void markDone(LocalDateTime now) {
    this.lastAttemptedAt = now;
    this.lastExecutedAt = now;
    this.failureCount = 0;
    this.lastError = null;
    this.status = ScheduledTaskStatus.DONE;
  }

  /**
   * 핸들러가 예외 없이 반환했고 재실행이 필요할 때(done=false, cap 이전, 1회성 아님)만
   * Executor가 호출한다. 실패 이력을 지우는 이유: 이번 실행이 성공했으므로 이전 실패는
   * 더 이상 backoff 계산에 영향을 주면 안 된다.
   */
  public void markSucceeded(LocalDateTime now) {
    this.lastExecutedAt = now;
    this.lastAttemptedAt = now;
    this.failureCount = 0;
    this.lastError = null;
    // 다음 확인 시각 = 지금 + interval. 이 값을 스스로 갱신하기 때문에 Runner 쪽에는
    // "몇 번째 실행인지" 계산 로직이 전혀 없다 — 폴링은 항상 nextAttemptAt만 비교하면 된다.
    this.nextAttemptAt = now.plusSeconds(intervalSeconds);
  }

  /**
   * 핸들러가 예외를 던졌을 때 Executor의 catch 블록이 호출한다. 실패해도 이 task는
   * status=PENDING을 유지한다 — nextAttemptAt만 미뤄서 "잠시 후 같은 폴링 루프가 다시
   * 집어가게" 만드는 것이 재시도의 전부다(별도 재시도 큐가 없다).
   */
  public void markFailed(LocalDateTime now, String errorMessage, int maxFailureCount,
      Duration baseBackoff, Duration maxBackoff) {
    this.lastAttemptedAt = now;
    this.failureCount++;
    this.lastError = truncate(errorMessage);
    // maxFailureCount(5)번째 실패부터는 nextAttemptAt을 아예 갱신하지 않고 FAILED로
    // 격리한다 — status가 PENDING이 아니게 되므로 findDueTaskIds가 더 이상 이 행을
    // 찾지 못해 자동으로 폴링 대상에서 빠진다.
    if (this.failureCount >= maxFailureCount) {
      this.status = ScheduledTaskStatus.FAILED;
      return;
    }
    // 지수 백오프: 실패 1회째 30초 × 2^1 = 60초, 2회째 120초, 3회째 240초, 4회째 480초
    // 뒤로 다음 시도를 미룬다. maxBackoff(30분)로 상한을 둬서 실패가 반복돼도 간격이
    // 무한히 늘어나지 않게 한다 — 원인이 잠깐의 네트워크/DB 장애라면 30분 안에는 다시
    // 시도해볼 수 있어야 하기 때문이다.
    long backoffSeconds = Math.min(
        baseBackoff.getSeconds() * (1L << this.failureCount), maxBackoff.getSeconds());
    this.nextAttemptAt = now.plusSeconds(backoffSeconds);
  }

  private static String truncate(String message) {
    if (message == null) {
      return null;
    }
    return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
  }
}
