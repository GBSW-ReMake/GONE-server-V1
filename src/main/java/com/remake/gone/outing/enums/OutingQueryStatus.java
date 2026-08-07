package com.remake.gone.outing.enums;

/**
 * 외출증 조회 {@code status} 필터 전용 enum(#41).
 *
 * <p>{@link OutingStatus}를 그대로 재사용하지 않는 이유: {@code DEPARTED}/{@code RETURNED}는
 * 출발/도착 보고 엔드포인트(마스터 기획서 4/5번)가 아직 없어 어떤 외출증도 그 상태에 도달할 수
 * 없다. 응답 DTO의 {@code status} 필드는 그 값들을 포함하는 {@link OutingStatus}를 그대로 써서
 * 나중에 그 엔드포인트가 생겼을 때 스키마 변경 없이 확장되게 하지만, 필터는 "지금 실제로 나올
 * 수 있는 값"만 받아 죽은 옵션을 만들지 않는다 — 그 값으로 필터링을 시도하면(요청 파라미터
 * 바인딩 단계에서) 조용히 빈 결과를 주지 않고 {@code 400}으로 명시적으로 알린다("모순된 조합은
 * 조용히 무시하지 않는다"는 이 기획의 엄격 모드 원칙과 일관됨).
 */
public enum OutingQueryStatus {
  PENDING,
  APPROVED,
  REJECTED,
  MISSED;

  /**
   * 응답 DTO의 {@link OutingStatus}와 비교할 수 있는 값으로 변환합니다. 값 이름이 서로
   * 1:1로 일치하도록 관리되므로 별도 매핑 테이블 없이 이름으로 변환합니다.
   *
   * @return 이름이 같은 {@link OutingStatus} 값
   */
  public OutingStatus toOutingStatus() {
    return OutingStatus.valueOf(name());
  }
}
