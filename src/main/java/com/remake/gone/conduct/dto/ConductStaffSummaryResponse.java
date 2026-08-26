package com.remake.gone.conduct.dto;

/**
 * 교사·선도부·관리자용 특정 학생 누적 상/벌점 요약 응답 DTO.
 *
 * <p>{@link ConductSummaryResponse}(학생 본인 조회용)에 조회 대상 학생 식별 정보를 추가한 구조다.
 *
 * @param studentUserId       조회 대상 학생 사용자 ID
 * @param studentNickname     조회 대상 학생 별명
 * @param totalMeritPoints    전체 기간 상점 합계(양수)
 * @param totalDemeritPoints  전체 기간 벌점 합계(음수로 저장된 값 그대로)
 * @param netScore            순 점수({@code totalMeritPoints + totalDemeritPoints})
 * @param demeritThreshold    벌점 임계치(설정값)
 * @param overDemeritThreshold 누적 벌점 절댓값이 임계치 이상인지 여부
 */
public record ConductStaffSummaryResponse(
    Long studentUserId,
    String studentNickname,
    int totalMeritPoints,
    int totalDemeritPoints,
    int netScore,
    int demeritThreshold,
    boolean overDemeritThreshold
) {}
