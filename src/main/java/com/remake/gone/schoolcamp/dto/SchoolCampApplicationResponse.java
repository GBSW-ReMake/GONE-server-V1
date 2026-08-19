package com.remake.gone.schoolcamp.dto;

import java.util.List;

/**
 * 스쿨캠핑 신청 결과 응답 DTO.
 *
 * @param id                 생성된 신청 ID
 * @param campDate           신청한 세션 날짜. {@code yyyyMMdd} 형식
 * @param teacherDisplayName 담당 선생님 실명(가입 여부와 무관하게 표시용 이름 하나로 통일)
 * @param members            팀원 전체 목록(대표 신청자 포함)
 * @param appliedAt          신청 시각(ISO-8601, 예: {@code "2026-03-20T09:12:00"})
 */
public record SchoolCampApplicationResponse(
    Long id,
    String campDate,
    String teacherDisplayName,
    List<SchoolCampMemberResponse> members,
    String appliedAt
) {}
