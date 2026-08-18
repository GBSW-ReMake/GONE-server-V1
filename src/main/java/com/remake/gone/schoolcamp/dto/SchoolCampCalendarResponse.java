package com.remake.gone.schoolcamp.dto;

import com.remake.gone.schoolcamp.enums.SchoolCampStatus;

/**
 * 스쿨캠핑 캘린더 조회 응답 DTO.
 *
 * @param sessionId           세션 ID
 * @param campDate            세션 날짜. {@code yyyyMMdd} 형식
 * @param status              점유 상태
 * @param teacherDisplayName  담당 선생님 실명. 아직 신청이 없으면 {@code null}
 * @param applicantDisplayName 대표 신청자의 "학번+실명"(예: {@code "3218정문경"}). 아직
 *                              신청이 없으면 {@code null}
 */
public record SchoolCampCalendarResponse(
    Long sessionId,
    String campDate,
    SchoolCampStatus status,
    String teacherDisplayName,
    String applicantDisplayName
) {}
