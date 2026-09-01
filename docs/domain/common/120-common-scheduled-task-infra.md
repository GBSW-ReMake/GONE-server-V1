# #120 범용 이벤트 스케줄링 인프라 구현 (scheduled_task 테이블 기반) — 기획서

관련 이슈: [#120 범용 이벤트 스케줄링 인프라 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/120)
계기: [#99 외출증 복귀 리마인더 스케줄러](../outing/99-outing-return-reminder.md) — v4까지
이 문서 안에서 다루던 범용 스케줄러 설계를 분리했다(2026-09-01). 재사용 예정 이슈:
[#99](../outing/99-outing-return-reminder.md)(outing 타임아웃 핸들러), #102(승인됐지만
출발 안 한 외출증 처리).

## 개요/목적
여러 도메인이 "특정 시각 이후 조건을 확인해 알림을 보낸다" 같은 정밀 건별 스케줄링을
공통으로 쓸 수 있는 인프라를 만든다. `scheduled_task` 테이블 하나로 예약 상태를 저장하고,
`task_type` 컬럼으로 도메인을 구분해 도메인마다 폴링 로직을 새로 만들지 않게 한다. 새
HTTP 엔드포인트는 없다 — `common/schedule` 패키지의 신규 컴포넌트가 산출물이다.

## 대안 비교 및 채택 근거
**전제(보스 확인, 2026-08-28)**:
- **정밀도**: ±10초 내외. 마감 시각과 알림 발송 사이 지연이 이 범위 안이어야 한다.
- **다중 인스턴스**: 전환 계획 없음(현재 시점 기준). 계획이 바뀌면 아래 결론을 다시
  검토한다.

**검토한 대안과 기각 이유**:
1. **순수 폴링(`@Scheduled(fixedDelay=...)`, #42 스타일, 도메인 테이블에 컬럼 추가)**:
   ±10초 정밀도는 5~10초 주기 폴링으로 맞출 수 있고(하루 8,640회 폴링, 건당 1~2ms 쿼리라
   DB 부하는 무시할 수준), 성능상 기각 사유는 없다. 다만 도메인 테이블에 `lastNotifiedAt`
   같은 컬럼을 매번 추가하면 도메인이 늘 때마다 같은 패턴을 중복 구현하게 된다 — 이 문제는
   "폴링을 거부할 이유"가 아니라 "폴링 로직을 어디에 둘 것이냐"의 문제라, 이 이슈는 같은
   폴링 아이디어를 범용 테이블 위에서 채택한다(아래 "채택" 참고).
2. **인메모리 `TaskScheduler`**: 예약 상태가 JVM 메모리에만 있어 서버가 꺼지면 사라진다.
   재시작 복구 로직(다운타임 동안 마감 넘긴 건 처리 포함)을 직접 설계·구현·검증해야 한다.
3. **Redis(예약 상태 저장 또는 keyspace notification)**: 예약 상태 저장은 원시 데이터가
   이미 도메인 테이블에 있어 중복 저장이고, keyspace notification은 Redis 공식 문서가
   이벤트 전달을 보장하지 않는다고 명시한다. 다중 인스턴스 계획이 없어 Redis의 "공유 상태"
   이점도 지금은 실익이 없다.
4. **Quartz + JDBC JobStore**: 설정 복잡도(커스텀 `JobFactory` 필요), 스키마 크기
   (`QRTZ_*` 11개 테이블), 재시작 안전망 구현 복잡도가 크다.
5. **JobRunr + SQL 저장소**: 재시작 복구를 프레임워크가 대신 해주고 설정이 간단하다는
   이점이 있지만, 자체 `scheduled_task` 테이블로 동일하게(오히려 더 낫게) 얻을 수 있다.
   - **모니터링 이득이 사라짐**: JobRunr 채택 이유 중 하나가 내장 대시보드였는데, 이
     프로젝트는 모니터링을 나중에 직접 붙일 계획이라(아래 "향후 모니터링과의 관계" 참고)
     대시보드를 어차피 안 쓴다. 남는 건 상시 워커 스레드 + 대시보드 HTTP 서버 리소스뿐이다.
   - **도메인 상태와 스케줄 상태가 분리됨**: JobRunr를 쓰면 도메인의 실제 상태(예: `outing`
     테이블)와 스케줄 상태(`jobrunr_jobs` 테이블)가 서로 다른 테이블에 있어, "이 건이 지금
     어떤 스케줄 상태인지" 확인하려면 두 테이블을 다 봐야 한다. 자체 테이블이면
     `scheduled_task` 하나만 보면 시스템 전체에 지금 예약된 게 뭔지 한눈에 파악된다.
   - **새 의존성 + 새 인프라 테이블 4개**: `jobrunr-spring-boot-3-starter` 의존성과
     `jobrunr_jobs`/`jobrunr_recurring_jobs`/`jobrunr_backgroundjobservers`/`jobrunr_metadata`
     4개 테이블이 통째로 사라진다. 대신 `scheduled_task` 테이블 1개만 추가한다.
   - **원자성이 더 좋아짐**: JobRunr는 "도메인 DB 커밋"과 "JobRunr에 잡 등록"이 서로 다른
     저장소에 대한 별개의 호출이라, 그 사이에 서버가 죽으면 예약이 누락되는 좁은 레이스가
     있다. `scheduled_task`는 도메인 테이블과 **같은 MySQL 안의 같은 트랜잭션**에서
     INSERT/DELETE하므로 이 레이스가 사라진다.

**채택**: `scheduled_task` 테이블 + `@Scheduled(fixedDelay=10000)` 폴링 + `task_type`별
핸들러 위임 구조로 간다. 1번(순수 폴링)이 거부됐던 이유(도메인마다 컬럼/로직 중복)를
없애면서, 5번(JobRunr)이 준 이점(범용성, 재시작 안전성)은 그대로 가져오는 절충안이다.

## 데이터 모델

### 신규 마이그레이션 — `scheduled_task` 테이블
파일명은 [migration-convention.md](../../rules/migration-convention.md)에 따라 실제
커밋 시점의 KST 타임스탬프로 확정한다(예시 형식:
`V{yyyyMMddHHmmss}__add_scheduled_task.sql`).
```sql
-- 범용 이벤트 스케줄 테이블 (#120). 특정 도메인 전용이 아니라 여러 도메인이 공유하는
-- 공용 인프라 테이블 — task_type 컬럼으로 도메인을 구분해 같은 폴링 루프를 공유한다.
CREATE TABLE scheduled_task (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_type         VARCHAR(50) NOT NULL,
    reference_id      BIGINT NOT NULL,
    scheduled_at      DATETIME NOT NULL,
    interval_seconds  INT NULL,
    end_at            DATETIME NULL,
    next_attempt_at   DATETIME NOT NULL,
    last_executed_at  DATETIME NULL,
    last_attempted_at DATETIME NULL,
    failure_count     INT NOT NULL DEFAULT 0,
    last_error        VARCHAR(500) NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_scheduled_task_type_ref (task_type, reference_id),
    KEY idx_scheduled_task_due (status, next_attempt_at)
);
```
컬럼별 역할:
- `scheduled_at`: 최초 실행 예정 시각. 등록 이후 값이 바뀌지 않는다.
- `interval_seconds`: 성공 실행 후 재실행 간격. `NULL`이면 1회성 작업이다.
- `end_at`: 발송 상한 시각(cap). 이 시각을 지나면 폴링이 다음에 이 task를 집어갈 때
  `handler.handle()`을 호출하지 않고 곧바로 `DONE` 처리한다.
- `next_attempt_at`: 폴링이 조회 기준으로 삼는 컬럼이다. 최초 등록 시 `scheduled_at`과
  같은 값으로 시작하고, 매 실행(성공/실패) 후 다시 계산한다(아래 "실행 결과 반영" 참고).
- `last_executed_at`: 마지막으로 **성공**한 실행 시각. 실패한 시도는 갱신하지 않는다.
- `last_attempted_at`: 마지막 시도 시각(성공/실패 무관). 모니터링에서 "이 task가 최근에
  실제로 시도됐는지"를 확인하는 용도다.
- `failure_count`: 연속 실패 횟수. 성공하면 0으로 초기화한다.
- `last_error`: 마지막 실패 시 예외 메시지(500자 초과 시 자른다). 실패 원인을 로그 없이도
  테이블 조회만으로 확인할 수 있게 한다.
- `status`: `PENDING`(대기/재시도 중), `DONE`(정상 종료), `FAILED`(재시도 상한 초과, 수동
  개입 필요) 세 값을 가진다.
- `task_type` + `reference_id` 유니크 제약으로 같은 대상에 대한 중복 등록을 DB 차원에서
  막는다.
- `idx_scheduled_task_due`는 폴링 조회(`status='PENDING' AND next_attempt_at <= now`)가
  인덱스를 타게 한다.

## `common/schedule` 패키지

### `ScheduledTaskStatus`
```java
package com.remake.gone.common.schedule;

public enum ScheduledTaskStatus { PENDING, DONE, FAILED }
```

### `ScheduledTask` 엔티티
```java
package com.remake.gone.common.schedule;

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
   * 나머지를 잘라버려서(예: 500ms → 0초) markSucceeded()가 즉시 다음 시도를 예약하는 결과를
   * 낳을 수 있고, 초 단위 값이 int 범위를 넘으면 캐스팅 시 조용히 값이 깨진다 — 둘 다 호출
   * 시점에 막는다.
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
   * 더 이상 재실행하지 않도록 상태를 종료 처리한다. markSucceeded()와 동일하게 시도/실행
   * 시각을 남기고 실패 이력을 지운다 — 그렇지 않으면 실패를 몇 번 거친 뒤 성공해 DONE
   * 처리된 task가 last_attempted_at/last_executed_at이 여전히 null이거나
   * failure_count/last_error가 남아있는 채로 보여, 이 테이블만 보고 상태를 판단하려는
   * 모니터링 취지(아래 "향후 모니터링과의 관계" 절)와 어긋난다.
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
```
**`@Version`(낙관적 락) 미도입 (보스 확인, 2026-09-01)**: #42의 `Outing` 엔티티는 스케줄러가
읽어간 스냅샷과 그 사이 커밋된 다른 트랜잭션의 충돌을 감지하려고 `@Version`을 추가했다(같은
행이 살아있는 채로 필드 일부만 바뀌는 시나리오). `ScheduledTask`는 이 행을 건드리는 쓰기가
`Executor`(자기 자신), `schedule()`(PENDING이면 no-op), `cancel()`(DELETE) 셋뿐이라 그
시나리오 자체가 없다 — `cancel()`이 먼저 커밋되면 `Executor`의 이후 UPDATE는 영향받는 행이
0개라 조용히 무의미해질 뿐 데이터 손상은 없다. 오히려 `@Version`을 넣으면 이 0행 UPDATE가
`OptimisticLockException`을 던지게 되는데, 이 예외는 `ScheduledTaskExecutor.execute`의
`try/catch`(핸들러 실행만 감싼다) 밖, 트랜잭션 커밋 시점에 발생해 지금 구조로는 못 잡는다 —
못 잡은 예외가 `Runner.run()`의 `forEach` 루프 밖으로 전파돼 같은 폴링 틱의 나머지 due
task까지 스킵시키는 새 실패 모드를 만든다. 따라서 `@Version`은 도입하지 않는다.

### `ScheduledTaskRepository`
```java
package com.remake.gone.common.schedule;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

  Optional<ScheduledTask> findByTaskTypeAndReferenceId(String taskType, Long referenceId);

  void deleteByTaskTypeAndReferenceId(String taskType, Long referenceId);

  @Query("select t.id from ScheduledTask t where t.status = :status and t.nextAttemptAt <= :now")
  List<Long> findDueTaskIds(
      @Param("status") ScheduledTaskStatus status, @Param("now") LocalDateTime now);
}
```
`status`를 문자열 리터럴이 아니라 파라미터로 바인딩하는 이유: JPQL에서 enum 필드를 문자열
리터럴과 직접 비교하는 동작은 Hibernate 구현에 따라 보장되지 않는다 — enum 파라미터
바인딩이 타입 안전하고, 향후 Hibernate 버전이 바뀌어도 흔들리지 않는다.
`findDueTaskIds`가 엔티티가 아니라 ID만 반환하는 이유: 실제 실행은 아래
`ScheduledTaskExecutor`가 건별 독립 트랜잭션에서 다시 조회해 처리한다. 조회 시점과 실행
시점 사이에 취소(`cancel`)나 다른 실행이 끼어들 수 있으므로, 오래된 스냅샷을 그대로
쓰지 않고 실행 직전에 항상 최신 상태를 다시 읽는다.

### `RetryPolicy` — task_type별 재시도 정책
```java
package com.remake.gone.common.schedule;

/**
 * 핸들러 실행 실패 시 재시도 방식을 정의한다. {@code maxFailureCount}번 연속 실패하면
 * FAILED로 격리하고, 그 전까지는 {@code baseBackoff × 2^실패횟수}(최대 {@code maxBackoff})
 * 간격으로 재시도한다(계산 로직은 {@code ScheduledTask.markFailed} 참고).
 */
public record RetryPolicy(int maxFailureCount, Duration baseBackoff, Duration maxBackoff) {

  /**
   * ScheduledTask.markFailed가 이 값들을 검증 없이 그대로 계산에 쓴다 — 여기서 막지
   * 않으면 maxFailureCount<=0은 첫 실패에 곧장 FAILED로 격리시키고, 0 이하이거나
   * 소수점 초 단위인 backoff는 nextAttemptAt이 매 폴링마다 계속 due 상태이거나 과거로
   * 계산되게 만든다.
   */
  public RetryPolicy {
    if (maxFailureCount <= 0) {
      throw new IllegalArgumentException("maxFailureCount는 1 이상이어야 합니다: " + maxFailureCount);
    }
    requirePositiveWholeSeconds(baseBackoff, "baseBackoff");
    requirePositiveWholeSeconds(maxBackoff, "maxBackoff");
    if (maxBackoff.compareTo(baseBackoff) < 0) {
      throw new IllegalArgumentException(
          "maxBackoff는 baseBackoff 이상이어야 합니다: baseBackoff=" + baseBackoff
              + ", maxBackoff=" + maxBackoff);
    }
  }

  private static void requirePositiveWholeSeconds(Duration duration, String name) {
    if (duration == null) {
      throw new IllegalArgumentException(name + "은 null일 수 없습니다");
    }
    if (duration.isNegative() || duration.isZero() || duration.getNano() != 0) {
      throw new IllegalArgumentException(name + "은 1초 이상의 정수초 단위 Duration이어야 합니다: " + duration);
    }
  }

  /** 별도로 재정의하지 않는 모든 핸들러가 쓰는 기본값 — 5회 실패, 30초~30분 백오프. */
  public static final RetryPolicy DEFAULT =
      new RetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(30));
}
```
`record`로 만든 이유: 세 값이 항상 함께 다니는 불변 값 객체라 Lombok 엔티티 스타일보다
자바 표준 `record`가 더 적합하고, 필드 3개짜리 getter/생성자 보일러플레이트가 없어진다.

### `ScheduledTaskHandler` — 도메인이 구현하는 접점
```java
package com.remake.gone.common.schedule;

public interface ScheduledTaskHandler {

  /**
   * 도메인 조건을 확인하고 필요하면 알림 등 부수 효과를 실행한다.
   *
   * @return 이 task를 더 이상 재실행할 필요가 없으면 true, 계속 재실행해야 하면 false.
   *     예외를 던지면 실패로 기록되고 재시도된다(재시도 방식은 아래 {@code retryPolicy()} 참고).
   */
  boolean handle(Long referenceId);

  /**
   * 이 task_type의 재시도 정책. 재정의하지 않으면 {@link RetryPolicy#DEFAULT}를 쓴다.
   * 예: 알림 발송처럼 실패해도 사용자 체감 영향이 적은 핸들러는 기본값을 그대로 쓰고,
   * 실패가 곧 사용자에게 보이는 핸들러라면 {@code maxFailureCount}를 늘리거나
   * {@code baseBackoff}를 줄여 더 집요하게 재시도하도록 오버라이드할 수 있다.
   */
  default RetryPolicy retryPolicy() {
    return RetryPolicy.DEFAULT;
  }
}
```
도메인 구현체는 `@Component("{TASK_TYPE 문자열}")`처럼 `task_type`을 빈 이름으로 등록한다
— Spring이 `Map<String, ScheduledTaskHandler>` 타입으로 모든 구현체를 자동 주입해주므로
별도 설정 없이 매핑된다. 재시도 정책을 `ScheduledTask` 행이 아니라 핸들러 쪽에 둔 이유는
"이 작업이 실패에 얼마나 민감한지"가 등록 시점(`schedule` 호출)의 정보가 아니라 그
task_type 자체의 고정된 성격이기 때문이다 — 매번 `schedule`을 호출하는 도메인 코드에서
정책값을 반복해서 넘기지 않아도 된다.

### `ScheduledTaskService` — 등록/취소
```java
package com.remake.gone.common.schedule;

@Component
@RequiredArgsConstructor
public class ScheduledTaskService {

  private final ScheduledTaskRepository scheduledTaskRepository;

  /**
   * 같은 (taskType, referenceId)가 이미 PENDING이면 무시하고, DONE/FAILED로 끝난 이전
   * 건이 있으면 정리한 뒤 새로 등록한다.
   */
  @Transactional
  public void schedule(String taskType, Long referenceId, LocalDateTime scheduledAt,
      Duration interval, Duration cap) {
    Optional<ScheduledTask> existing =
        scheduledTaskRepository.findByTaskTypeAndReferenceId(taskType, referenceId);
    if (existing.isPresent()) {
      // 이미 PENDING(대기/재시도 중)이면 새로 등록하지 않고 그대로 둔다 — 예를 들어
      // departOuting이 같은 outing에 대해 실수로 두 번 호출돼도 예약이 중복되지 않는다.
      if (existing.get().getStatus() == ScheduledTaskStatus.PENDING) {
        return;
      }
      // DONE/FAILED로 이미 끝난 이전 건이면 지운다 — 유니크 제약(task_type, referenceId)
      // 때문에 지우지 않고는 같은 대상을 다시 등록할 수 없다(위 "DONE/FAILED 정리 후
      // 재등록하는 이유" 참고).
      // flush로 DELETE를 즉시 실행시킨다 — ScheduledTask는 GenerationType.IDENTITY라
      // 아래 save()가 즉시 INSERT를 실행하는데(IDENTITY는 생성된 PK를 바로 알아야 해서
      // Hibernate가 flush까지 미루지 못한다), flush 없이는 아직 DB에 남아있는 이 행과
      // 유니크 제약(task_type, reference_id)이 충돌해 save()가 실패한다.
      scheduledTaskRepository.delete(existing.get());
      scheduledTaskRepository.flush();
    }
    scheduledTaskRepository.save(
        new ScheduledTask(taskType, referenceId, scheduledAt, interval, cap));
  }

  @Transactional
  public void cancel(String taskType, Long referenceId) {
    scheduledTaskRepository.deleteByTaskTypeAndReferenceId(taskType, referenceId);
  }
}
```
`schedule`/`cancel`은 호출하는 도메인 서비스의 트랜잭션에 참여한다(기본 전파
`REQUIRED`) — 예를 들어 outing의 `departOuting`/`returnOuting`이 이미 `@Transactional`
이므로, 그 안에서 호출하면 도메인 데이터와 `scheduled_task` 행이 같은 트랜잭션으로
원자적으로 커밋/롤백된다.

**DONE/FAILED 정리 후 재등록하는 이유**: 유니크 제약(`task_type`, `reference_id`)이 있어,
한 번 끝난(`DONE`) 건이 테이블에 남아있으면 같은 대상에 대한 이후 `schedule` 호출이 영원히
막힌다. 지금 이 이슈의 유일한 소비자(#99의 `OUTING_TIMEOUT`)는 매번 새 `Outing` 행(새
`referenceId`)에 대해서만 `schedule`을 호출해 이 문제를 실제로 겪지 않지만, 앞으로 같은
`referenceId`에 반복 등록이 필요한 도메인(예: 주기적으로 재발동하는 리마인더)이 이
인프라를 재사용할 수 있으므로 처음부터 안전하게 설계한다.

### `ScheduledTaskRunner` — 폴링 루프 (도메인 무관)
```java
package com.remake.gone.common.schedule;

@Component
@RequiredArgsConstructor
public class ScheduledTaskRunner {

  private final ScheduledTaskRepository scheduledTaskRepository;
  private final ScheduledTaskExecutor scheduledTaskExecutor;

  // fixedDelay: 이전 실행이 "끝난 뒤" 10초 후 다시 돈다(fixedRate 아님) — due 건이 많아
  // 한 틱 처리가 10초를 넘겨도 다음 틱과 겹쳐 실행되지 않는다(#42와 동일한 안전 기본값).
  @Scheduled(fixedDelay = 10_000)
  public void run() {
    // 조회 기준 시각은 한 번만 고정한다 — 이 틱에서 "무엇이 due인지"의 기준은 틱 시작
    // 시점이어야 일관된다. 반면 각 task를 실제로 처리하는 시각(execute에 넘기는 now)은
    // task마다 다시 구한다 — due 건이 많아 앞 task 처리가 오래 걸리면, 뒤 task는 틱 시작
    // 시각보다 실제로 몇 초 늦게 처리되는데 그 stale한 시각으로 cap 판정/백오프를 계산하면
    // 안 되기 때문이다.
    LocalDateTime tickStartedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    scheduledTaskRepository.findDueTaskIds(ScheduledTaskStatus.PENDING, tickStartedAt)
        .forEach(taskId -> scheduledTaskExecutor.execute(taskId, LocalDateTime.now(ZoneId.of("Asia/Seoul"))));
  }
}
```
`Runner`는 트리거 역할만 한다 — 조회는 `ScheduledTaskRepository`(Spring Data 기본
읽기전용 트랜잭션), 실제 실행/상태 변경은 `ScheduledTaskExecutor`(아래)에 위임한다.

### `ScheduledTaskExecutor` — 건별 실행 + 실패 처리
```java
package com.remake.gone.common.schedule;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledTaskExecutor {

  private final ScheduledTaskRepository scheduledTaskRepository;
  private final Map<String, ScheduledTaskHandler> handlers; // 빈 이름 = task_type

  // REQUIRES_NEW: Runner의 forEach 루프 안에서 호출되지만 매번 새 트랜잭션을 연다 —
  // 이 task의 성공/실패가 같은 루프에서 처리 중인 다른 task의 트랜잭션과 완전히
  // 분리된다(자세한 이유는 아래 "왜 REQUIRES_NEW인지" 참고).
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void execute(Long taskId, LocalDateTime now) {
    ScheduledTask task;
    try {
      // Runner가 넘긴 taskId로 다시 조회한다 — Runner가 findDueTaskIds로 읽은 시점과
      // 지금 사이에 다른 요청(예: returnOuting → cancel)이 이 행을 지웠거나 이미 다른
      // 스레드가 처리해 상태가 바뀌었을 수 있어, 그 경우 아무것도 하지 않고 조용히 끝낸다.
      task = scheduledTaskRepository.findById(taskId).orElse(null);
    } catch (Exception e) {
      // 조회 자체가 실패하면(예: 일시적 DB 커넥션 문제) 이 task는 건드리지 않고 넘어간다 —
      // next_attempt_at이 그대로라 다음 폴링 틱(10초 뒤)에 자동으로 다시 시도된다. 여기서
      // 예외를 삼키지 않으면 Runner의 forEach가 중단돼 같은 틱에서 아직 처리 안 한 나머지
      // task까지 이번 틱에서 스킵된다(아래 "왜 REQUIRES_NEW인지"가 보장하는 건별 격리가
      // 조회 단계에는 적용되지 않기 때문).
      log.error("ScheduledTask 조회 실패(id={})", taskId, e);
      return;
    }
    if (task == null || task.getStatus() != ScheduledTaskStatus.PENDING) {
      return; // 조회 이후 취소되거나 이미 처리됨
    }
    // cap(end_at)을 이미 넘겼으면 handler.handle()을 아예 호출하지 않고 바로 종료한다
    // (보스 확인, 2026-09-01) — 더 기다려도 의미가 없는 시점이 지났으므로, 핸들러가 부수
    // 효과(알림 발송 등)를 굳이 한 번 더 실행할 필요가 없다.
    if (task.isPastCap(now)) {
      task.markDone(now);
      return;
    }
    // task_type 문자열과 정확히 같은 빈 이름으로 등록된 핸들러를 찾는다(예: "OUTING_TIMEOUT").
    // 매핑이 없다는 건 배포 실수(핸들러 등록을 빠뜨림)일 가능성이 높아 경고만 남기고
    // 넘어간다 — 다음 폴링 틱에서 다시 같은 경고가 반복되므로 로그로 바로 드러난다.
    ScheduledTaskHandler handler = handlers.get(task.getTaskType());
    if (handler == null) {
      log.warn("등록된 ScheduledTaskHandler가 없습니다(taskType={})", task.getTaskType());
      return;
    }
    try {
      // 실제 도메인 로직은 전부 handler.handle() 안에 있다 — Executor는 그 결과를
      // 보고 "이 task를 어떻게 할지"만 결정한다(끝낼지/다시 예약할지/실패로 기록할지).
      boolean done = handler.handle(task.getReferenceId());
      // 둘 중 하나라도 참이면 더 이상 재실행하지 않는다: (1) 핸들러가 스스로 "끝났다"고
      // 판단, (2) 애초에 1회성 작업이라 재실행 개념이 없음. cap 판정은 위에서 이미 끝났다.
      if (done || task.isOneShot()) {
        task.markDone(now);
      } else {
        task.markSucceeded(now);
      }
    } catch (Exception e) {
      // 재시도 정책은 이 handler(=task_type)가 정의한 값을 쓴다 — 오버라이드하지
      // 않았으면 RetryPolicy.DEFAULT(5회, 30초~30분)가 그대로 적용된다.
      RetryPolicy retryPolicy = handler.retryPolicy();
      // 핸들러 내부 예외(알림 저장 실패, DB 순간 장애 등)를 여기서 잡아 트랜잭션을
      // 정상 커밋시킨다 — markFailed가 기록한 실패 카운트/다음 시도 시각까지 함께
      // 저장돼야 다음 폴링 틱이 그 값을 보고 재시도 여부를 판단할 수 있기 때문이다.
      // (예외를 그대로 던지면 이 트랜잭션 자체가 롤백되어 실패 기록조차 남지 않는다.)
      task.markFailed(now, e.getMessage(), retryPolicy.maxFailureCount(),
          retryPolicy.baseBackoff(), retryPolicy.maxBackoff());
      log.error("ScheduledTask 실행 실패(id={}, taskType={}, referenceId={}, failureCount={})",
          task.getId(), task.getTaskType(), task.getReferenceId(), task.getFailureCount(), e);
    }
  }
}
```
**`@Transactional(propagation = REQUIRES_NEW)`를 건별로 붙이는 이유(#99 v4 대비 정정)**:
v4 초안은 `Runner.run()` 전체를 하나의 트랜잭션으로 묶어 한 폴링 틱의 모든 due 건을
처리했다. 이 방식은 한 건의 `handle()` 호출이 예외를 던지면 트랜잭션 전체가 롤백되어,
같은 틱에서 이미 처리된 **다른 task_type/다른 대상의 실행 결과까지 함께 사라지는 문제**가
있었다(#42가 정확히 이 문제를 건별 독립 트랜잭션으로 해결한 선례와 반대 방향이었다). 이
설계는 `execute` 자체를 건별 새 트랜잭션(`REQUIRES_NEW`)으로 분리해, 한 건의 실패가
`Runner`가 같은 틱에서 처리하는 다른 건에 영향을 주지 않게 한다.
**`Runner`가 아니라 `Executor`라는 별도 빈에 이 메서드를 둔 이유**: 같은 클래스 안에서
`this.execute(...)`를 호출하면 스프링 AOP 프록시를 거치지 않아 `@Transactional`이
적용되지 않는다(자기 호출 문제) — 별도 빈으로 분리해 프록시를 통한 호출을 강제한다.

## 실패 처리와 백오프 (#99 검토에서 발견된 공백에 대한 결론)
- **건별 격리**: 위 "왜 REQUIRES_NEW인지" 참고 — 한 건의 실패가 다른 건을 되돌리지 않는다.
- **지수 백오프**: 실패할 때마다 `failureCount`를 늘리고, 다음 시도 시각을
  `now + min(baseBackoff × 2^failureCount, maxBackoff)`로 미룬다. 기본값(`RetryPolicy.DEFAULT`)
  기준으로 1회 실패 시 60초 뒤, 2회 실패 시 120초 뒤, 계속 실패해도 최대 30분 간격을
  넘지 않는다.
- **실패 격리(FAILED)**: `maxFailureCount`(기본값 5)회 연속 실패하면 `status=FAILED`로
  바꾸고 폴링 대상에서 제외한다(`findDueTaskIds`가 `status='PENDING'`만 조회하므로). 이
  시점부터는 자동 재시도가 없다 — `log.error`로 남긴 로그가 유일한 신호이고, 재활성화는
  수동 개입(향후 관리 API, 이번 이슈 범위 밖)이 필요하다.
- **task_type별 정책 오버라이드**: 위 `RetryPolicy`/`ScheduledTaskHandler.retryPolicy()`
  절 참고 — 재시도 정책(`maxFailureCount`/`baseBackoff`/`maxBackoff`)은 전역 상수가
  아니라 핸들러가 반환하는 값이다. 대부분의 핸들러는 `retryPolicy()`를 재정의하지 않아
  `RetryPolicy.DEFAULT`(5회, 30초~30분)를 그대로 쓰지만, 실패에 더 민감한 task_type이
  생기면(예: "실패하면 사용자가 바로 눈치채는" 종류) 해당 핸들러에서만 `retryPolicy()`를
  오버라이드해 더 짧은 백오프/더 많은 재시도 횟수를 줄 수 있다. `Executor`는 매 실패마다
  `handler.retryPolicy()`를 다시 읽으므로(고정 캐시 없음), 이 값을 바꾸는 데 재배포
  외에 별도 절차가 필요 없다.

## 향후 모니터링과의 관계
이 이슈는 모니터링 조회 API를 만들지 않는다 — 별도 관리 화면을 나중에 붙일 계획이고
(위 "대안 비교" 5번 참고), 그 화면의 요구사항이 아직 없어 지금 API를 설계하면 추측성
설계가 된다. 다만 그 화면이 나중에 이 테이블만 보고 "지금 뭐가 예약돼 있고, 뭐가 실패
중인지" 판단할 수 있도록 최소한의 실행 이력 컬럼(`last_attempted_at`, `failure_count`,
`last_error`, `status=FAILED`)을 지금 스키마에 포함해둔다. 이후 모니터링 이슈가 열리면
이 컬럼을 그대로 노출하는 조회 엔드포인트만 추가하면 되고, 별도 마이그레이션이 필요 없다.

## 다중 인스턴스 배포
현재 배포 환경은 단일 인스턴스를 전제한다(위 "전제" 참고). 인스턴스가 여러 개면
`ScheduledTaskRunner`의 폴링이 겹쳐 같은 task를 중복 실행할 수 있다 — 이를 막으려면
`UPDATE ... WHERE status='PENDING'` 조건부 갱신 같은 claim 로직이나 분산 락(#42가 언급한
`ShedLock` 등)이 필요한데, 지금은 만들지 않는다(YAGNI). 다중 인스턴스 전환이 실제로
결정되면 이 이슈로 돌아와 재검토한다.

## 영향 받는 기존 코드
- `build.gradle`/`application.yml`: 변경 없음(새 의존성 없음)
- 신규 마이그레이션: `scheduled_task` 테이블 1개
- 신규: `common/schedule/ScheduledTask`, `common/schedule/ScheduledTaskStatus`,
  `common/schedule/ScheduledTaskRepository`, `common/schedule/RetryPolicy`,
  `common/schedule/ScheduledTaskHandler`, `common/schedule/ScheduledTaskService`,
  `common/schedule/ScheduledTaskRunner`, `common/schedule/ScheduledTaskExecutor`
- 이 이슈는 실제 `ScheduledTaskHandler` 구현체(예: outing의 `OutingTimeoutScheduledTaskHandler`)를
  포함하지 않는다 — #99/#102가 각자 이 인터페이스를 구현해 후속으로 등록한다.
- `GoneServerV1Application`: 변경 없음(`@EnableScheduling`은 #42에서 이미 활성화됨)

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 새 엔드포인트 없음, 해당 없음.
2. **빠르게 시작하기**: 해당 없음(백그라운드 인프라, 소비자는 `ScheduledTaskHandler`
   인터페이스 하나만 구현하면 된다 — 아래 테스트 방법의 더미 핸들러가 최소 사용 예시
   역할을 한다).
3. **일관성**: 스케줄러는 트리거, 로직은 도메인 서비스라는 기존(#42) 컨벤션을 그대로
   따른다.
4. **의미 있는 오류**: 신규 `ErrorCode` 없음(HTTP 엔드포인트가 아니라 배치 인프라). 실패
   원인은 `last_error` 컬럼과 로그로 노출한다(위 "실패 처리와 백오프" 참고).
5. **확장성/성능**: 폴링 주기(10초)는 학교 규모(도메인당 하루 수 건) 기준 과분하다.
   도메인이 늘어도 폴링 루프는 그대로 공유되므로 상시 리소스가 늘지 않는다. 건별 트랜잭션
   분리로 due 건수가 늘어도 트랜잭션 하나가 길어지지 않는다(v4 대비 개선).
6. **하위 호환성**: 신규 테이블/신규 패키지만 추가되므로 기존 API 요청/응답 스키마에
   영향이 없다.

## 알려진 제약 (경미, 의도된 트레이드오프)
- **폴링 지연**: `fixedDelay=10_000`(10초)이므로 최초 실행이 예정 시각보다 최대 10초
  늦을 수 있다 — ±10초 정밀도 요구사항 안에 들어온다.
- **다중 인스턴스 배포 시 중복 실행**: 위 "다중 인스턴스 배포" 절 참고.
- **FAILED 이후 자동 복구 없음**: `retryPolicy().maxFailureCount()`만큼 연속 실패하면
  수동 개입 전까지 재시도하지 않는다(위 "실패 처리와 백오프" 참고) — 무한 재시도로
  다운스트림을 계속 두드리는 것보다 안전한 방향이라 판단했다.
- **과거 시각으로 스케줄될 때의 동작**: `scheduledAt`이 이미 지난 시각이어도
  `next_attempt_at <= now` 조건에 자연히 걸려 다음 폴링 틱에 바로 처리된다.

## 검토 후 채택하지 않기로 결정한 대안
위 "대안 비교 및 채택 근거" 절 1~5번 참고(순수 폴링의 컬럼 중복 문제, 인메모리
`TaskScheduler`의 재시작 복구 부담, Redis의 이벤트 전달 미보장, Quartz의 설정/스키마
복잡도, JobRunr의 모니터링 이득 상실과 원자성 약화).

## 아직 결정 안 된 것 (리뷰 필요)
- `RetryPolicy.DEFAULT`(30초/30분/5회)는 실제 소비자가 없는 상태에서 정한 초기값이다.
  #99가 `retryPolicy()`를 오버라이드하지 않고 기본값을 그대로 쓸 예정이라, 실제로 QA를
  거치면 이 기본값이 적절한지(특히 `maxFailureCount=5`가 outing 알림 발송 실패 빈도에
  비해 너무 관대하거나 너무 엄격하지 않은지) 재검토가 필요할 수 있다.
- 다중 인스턴스 claim 로직 도입 시점 — 배포 환경 계획이 바뀌면 재검토(위 "다중 인스턴스
  배포" 참고).
- **`execute()` 안에서 handler 실행과 실패 기록이 같은 트랜잭션을 공유하는 문제(코드 리뷰
  Major, 보류 확정 2026-09-01)**: `execute()`는 `REQUIRES_NEW`로 트랜잭션 하나를 열고 그
  안에서 `handler.handle()` 호출과 실패 시 `task.markFailed(...)` 기록을 함께 처리한다.
  `handler.handle()` 내부에서 호출하는 다른 `@Transactional(REQUIRED)` 서비스가 예외를
  던지면, Spring이 이 물리 트랜잭션 전체를 rollback-only로 표시할 수 있다 — 그러면
  `execute()`의 catch 블록이 `markFailed(...)`를 호출해도 그 변경까지 함께 롤백되고,
  트랜잭션 커밋 시점에 `UnexpectedRollbackException`이 (catch 블록 밖에서) 던져져 같은
  폴링 틱의 나머지 task까지 스킵될 수 있다. 고치려면 handler 실행과 실패 기록을 별도
  물리 트랜잭션으로 분리해야 하는데(자기 호출 문제 때문에 새 빈이 필요, `Runner`/
  `Executor`를 분리한 이유와 동일), 지금은 실제 `ScheduledTaskHandler` 구현체가 없어
  재현·검증이 불가능하다 — **#99가 실제 handler를 구현하는 시점에 같이 고치기로 보류
  확정**(보스 확인). #99 QA에서 참여 트랜잭션이 예외를 던지는 케이스를 실제로 재현해
  고친 뒤 검증한다.

## 테스트 방법
이 이슈 자체는 실제 도메인 핸들러를 포함하지 않으므로, 컴포넌트 단위 테스트로 검증하고
end-to-end 동작(실제 알림 발송까지 이어지는지)은 #99의 QA에서 확인한다.
1. `ScheduledTaskServiceTest`(신규): `schedule`이 PENDING 중복 등록을 무시하는지, DONE/
   FAILED로 끝난 이전 건을 정리하고 재등록하는지, `cancel`이 정상 삭제하는지 검증.
2. `ScheduledTaskExecutorTest`(신규, 테스트 전용 `ScheduledTaskHandler` 스텁 사용):
   - 핸들러가 `true` 반환 → `DONE` 처리
   - 핸들러가 `false` 반환 + `intervalSeconds` 있음 + `endAt` 이전 → `markSucceeded`로
     `nextAttemptAt`이 `now + intervalSeconds`로 갱신되는지
   - `intervalSeconds`가 `null`(1회성) → 핸들러가 `false`를 반환해도 `DONE` 처리되는지
   - `endAt`을 넘긴 건 → 핸들러 반환값과 무관하게 `DONE` 처리되는지
   - 핸들러가 예외를 던짐 + `retryPolicy()`를 재정의하지 않음 → `RetryPolicy.DEFAULT`
     기준으로 `failureCount` 증가, `nextAttemptAt`이 백오프만큼 미뤄지는지, 5회째에
     `FAILED`로 바뀌는지
   - 핸들러가 `retryPolicy()`를 재정의(예: `maxFailureCount=2`)한 상태에서 예외를 던짐
     → 2회째에 `FAILED`로 바뀌는지(전역 기본값이 아니라 핸들러가 준 값이 실제로 쓰이는지
     확인하는 게 핵심)
   - `handlers` 맵에 없는 `taskType` → 예외 없이 조용히 스킵(로그 경고)되는지
3. `RetryPolicyTest`(신규, 선택): `RetryPolicy.DEFAULT`가 문서에 명시한 값(5회, 30초,
   30분)과 실제로 일치하는지 — 상수값이 조용히 바뀌는 회귀를 잡는 용도.
4. `ScheduledTaskRunnerTest`(신규): `findDueTaskIds` 결과 각각에 대해
   `scheduledTaskExecutor.execute`를 호출하는지 검증(모킹).
5. 로컬 서버 기동 후 Flyway 마이그레이션이 정상 적용되는지 확인(새 테이블 1개, 새
   의존성 없음 — 라이브러리 버전 호환성 이슈가 없어 검증 범위가 좁다).

## 리스크 및 고려사항
- 위 "알려진 제약" 절 참고.
- `common/schedule` 패키지가 이 이슈로 처음 생기는 공용 인프라라, 실제 소비자(#99)가
  붙었을 때 인터페이스(`ScheduledTaskHandler.handle`의 `Long referenceId` 하나만 넘기는
  방식)가 충분한지 재검증이 필요할 수 있다.
- Notion API 명세서 반영 대상 아님(신규 엔드포인트 없음).
