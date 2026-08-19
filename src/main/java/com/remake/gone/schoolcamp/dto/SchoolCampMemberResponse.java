package com.remake.gone.schoolcamp.dto;

/**
 * 팀원 1명의 응답 DTO. 가입된 학생이면 실명/학년/반을, "기타"면 자유 입력 이름을 채운다.
 *
 * @param studentRealName 가입된 학생의 실명({@code Gbsw.name}). "기타"면 {@code null}
 * @param studentGrade    가입된 학생의 학년. "기타"면 {@code null}
 * @param studentClassNo  가입된 학생의 반. "기타"면 {@code null}
 * @param guestName       "기타"로 자유 입력한 이름. 가입된 학생이면 {@code null}
 * @param isApplicant     대표 신청자 본인이면 {@code true}
 */
public record SchoolCampMemberResponse(
    String studentRealName,
    Integer studentGrade,
    Integer studentClassNo,
    String guestName,
    boolean isApplicant
) {}
