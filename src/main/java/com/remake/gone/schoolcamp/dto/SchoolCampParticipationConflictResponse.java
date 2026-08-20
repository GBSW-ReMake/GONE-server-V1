package com.remake.gone.schoolcamp.dto;

import java.util.List;

/**
 * 이번 달 중복 참여(#81)로 신청/수정이 거부됐을 때, {@code 409 SCHOOLCAMP_003} 응답의
 * {@code data}에 실리는 정보.
 *
 * @param conflictingMembers 이번 요청 후보 중 이번 달에 이미 참여 중이었던 학생 목록
 */
public record SchoolCampParticipationConflictResponse(
    List<SchoolCampConflictingMemberResponse> conflictingMembers
) {}
