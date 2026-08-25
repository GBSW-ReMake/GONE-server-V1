package com.remake.gone.conduct.dto;

/**
 * 학생 본인 누적 상/벌점 요약 응답 DTO.
 *
 * @param totalMeritPoints    전체 기간 상점 합계(양수)
 * @param totalDemeritPoints  전체 기간 벌점 합계(음수로 저장된 값 그대로)
 * @param netScore            순 점수({@code totalMeritPoints + totalDemeritPoints})
 * @param demeritThreshold    벌점 임계치(설정값)
 * @param overDemeritThreshold 누적 벌점 절댓값이 임계치 이상인지 여부
 */
public record ConductSummaryResponse(
    int totalMeritPoints,
    int totalDemeritPoints,
    int netScore,
    int demeritThreshold,
    boolean overDemeritThreshold
) {}
