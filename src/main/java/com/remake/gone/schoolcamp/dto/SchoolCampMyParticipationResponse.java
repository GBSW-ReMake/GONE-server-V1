package com.remake.gone.schoolcamp.dto;

import com.remake.gone.schoolcamp.enums.SchoolCampMyRole;
import java.util.List;

/**
 * 본인 참여 신청 상세(#69, {@code GET /api/v1/school-camps/applications/{id}}) 응답 DTO.
 *
 * @param id                 신청 ID
 * @param campDate           참여한 세션 날짜. {@code yyyyMMdd} 형식
 * @param teacherDisplayName 담당 선생님 실명(가입 여부와 무관하게 표시용 이름 하나로 통일)
 * @param myRole             이 조회를 요청한 본인의 역할
 * @param members            팀원 전체 목록(대표 신청자 포함)
 * @param appliedAt          신청 시각(ISO-8601)
 * @param cancelledAt        취소 시각(ISO-8601). 유효한 신청이면 {@code null}
 */
public record SchoolCampMyParticipationResponse(
    Long id,
    String campDate,
    String teacherDisplayName,
    SchoolCampMyRole myRole,
    List<SchoolCampMemberResponse> members,
    String appliedAt,
    String cancelledAt
) {}
