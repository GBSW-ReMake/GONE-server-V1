package com.remake.gone.user.dto;

/**
 * 실명 검색 결과 DTO.
 *
 * @param userId    사용자 ID
 * @param nickname  서비스 내 별명({@code User.name})
 * @param realName  실명({@code Gbsw.name})
 * @param grade     학년. 학생이면 값, 선생님이면 {@code null}
 * @param classNo   반. 학생이면 값, 선생님이면 {@code null}
 */
public record UserSearchResponse(
    Long userId,
    String nickname,
    String realName,
    Integer grade,
    Integer classNo
) {}
