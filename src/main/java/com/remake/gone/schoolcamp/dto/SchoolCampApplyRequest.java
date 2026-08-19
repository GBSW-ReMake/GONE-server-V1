package com.remake.gone.schoolcamp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 스쿨캠핑 신청 요청 DTO. 대표 신청자 본인은 목록에 적지 않는다(자동 포함).
 *
 * <p>{@code teacherUserId}/{@code teacherName} 중 정확히 하나만 값이 있어야 한다 —
 * {@link SchoolCampMemberRequest}와 동일한 이유로 서비스 레벨에서 검증한다.
 *
 * @param teacherUserId    가입된 선생님을 고른 경우
 * @param teacherName      가입 안 된 선생님을 자유 입력한 경우
 * @param additionalMembers 대표 신청자 외 팀원 목록(0~7명). {@code null}이면 빈 목록으로 취급
 */
public record SchoolCampApplyRequest(
    Long teacherUserId,

    @Size(max = 50, message = "이름은 50자 이하로 입력해주세요")
    String teacherName,

    @Valid
    List<SchoolCampMemberRequest> additionalMembers
) {}
