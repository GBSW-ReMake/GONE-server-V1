package com.remake.gone.user.dto;

/**
 * 실명·학번 검색 결과 DTO.
 *
 * @param userId        사용자 ID
 * @param nickname      서비스 내 별명({@code User.name})
 * @param realName      실명({@code Gbsw.name})
 * @param studentNumber 학번(학년+반+번호 4자리, 예: {@code "3218"}). 학생이면 값, 선생님이면
 *                      {@code null}
 * @param grade         학년. 학생이면 값, 선생님이면 {@code null}
 * @param classNo       반. 학생이면 값, 선생님이면 {@code null}
 * @param number        반에서의 번호({@code Gbsw.number}). 학생이면 값, 선생님이면 {@code null}
 */
public record UserSearchResponse(
    Long userId,
    String nickname,
    String realName,
    String studentNumber,
    Integer grade,
    Integer classNo,
    Integer number
) {}
