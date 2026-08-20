package com.remake.gone.schoolcamp.dto;

import com.remake.gone.schoolcamp.enums.SchoolCampMyRole;

/**
 * 본인 참여 내역 목록(#69, {@code GET /api/v1/school-camps/me}) 항목 1건의 요약 응답 DTO.
 *
 * <p>팀원 전체는 담지 않는다 — 상세는 {@code GET /api/v1/school-camps/applications/{id}}로
 * 따로 조회한다(컬렉션 안에 컬렉션을 중첩하지 않는다는 API 설계 원칙 5 대응).
 *
 * @param id                 신청 ID. 상세 조회에 그대로 쓸 수 있다
 * @param campDate           참여한 세션 날짜. {@code yyyyMMdd} 형식
 * @param teacherDisplayName 담당 선생님 실명(가입 여부와 무관하게 표시용 이름 하나로 통일)
 * @param myRole             이 조회를 요청한 본인의 역할
 * @param appliedAt          신청 시각(ISO-8601)
 * @param cancelledAt        취소 시각(ISO-8601). 유효한 신청이면 {@code null}
 */
public record SchoolCampMyParticipationSummaryResponse(
    Long id,
    String campDate,
    String teacherDisplayName,
    SchoolCampMyRole myRole,
    String appliedAt,
    String cancelledAt
) {}
