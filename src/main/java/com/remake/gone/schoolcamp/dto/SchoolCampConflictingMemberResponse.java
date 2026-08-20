package com.remake.gone.schoolcamp.dto;

/**
 * 이번 달 중복 참여로 걸린 학생 1명의 정보(#81).
 *
 * @param studentUserId   걸린 학생의 사용자 ID
 * @param studentRealName 걸린 학생의 실명({@code Gbsw.name})
 * @param studentGrade    걸린 학생의 학년
 * @param studentClassNo  걸린 학생의 반
 */
public record SchoolCampConflictingMemberResponse(
    Long studentUserId,
    String studentRealName,
    Integer studentGrade,
    Integer studentClassNo
) {}
