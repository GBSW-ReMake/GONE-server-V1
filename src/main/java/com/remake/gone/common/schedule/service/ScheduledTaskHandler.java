package com.remake.gone.common.schedule.service;

/**
 * {@code common/schedule} 인프라 위에서 실제 도메인 로직을 실행하는 접점. 구현체는
 * {@code @Component("{TASK_TYPE 문자열}")}처럼 {@code task_type}을 빈 이름으로 등록한다 —
 * Spring이 {@code Map<String, ScheduledTaskHandler>} 타입으로 모든 구현체를 자동 주입해주므로
 * 별도 설정 없이 매핑된다.
 */
public interface ScheduledTaskHandler {

  /**
   * 도메인 조건을 확인하고 필요하면 알림 등 부수 효과를 실행한다.
   *
   * @param referenceId 도메인 엔티티의 PK.
   * @return 이 task를 더 이상 재실행할 필요가 없으면 true, 계속 재실행해야 하면 false.
   *     예외를 던지면 실패로 기록되고 재시도된다(재시도 방식은 {@link #retryPolicy()} 참고).
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
