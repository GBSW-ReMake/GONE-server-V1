package com.remake.gone.schoolcamp.dto;

import jakarta.validation.constraints.Size;

/**
 * 스쿨캠핑 신청/수정 요청의 팀원 1명 DTO.
 *
 * <p>{@code studentUserId}/{@code guestName} 중 정확히 하나만 값이 있어야 한다 —
 * 두 필드를 한 번에 검증할 수 없는 단순 애너테이션 대신 서비스 레벨에서 검증한다
 * ({@code SchoolCampService.validateMemberFormat} 참고).
 *
 * @param studentUserId 가입된 학생을 고른 경우
 * @param guestName     "기타"로 이름만 자유 입력한 경우
 */
public record SchoolCampMemberRequest(
    Long studentUserId,

    @Size(max = 50, message = "이름은 50자 이하로 입력해주세요")
    String guestName
) {}
