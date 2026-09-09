package com.remake.gone.outing.dto;

import com.remake.gone.outing.enums.OutingTimeSlot;
import java.time.LocalDateTime;

/**
 * 지금 외출 중인 학생 목록 응답 DTO(#96).
 *
 * @param code                   외부 식별자 코드(내부 PK가 아니라 프론트에 표시할 코드)
 * @param studentNickname        학생 별명({@code User.name})
 * @param studentProfileImageUrl 학생 프로필 사진 presigned URL. 없으면 {@code null}
 * @param studentRealName        학생 실명({@code Gbsw.name})
 * @param studentGrade           학생 학년
 * @param studentClassNo         학생 반
 * @param reason                 외출 사유
 * @param timeSlot               외출 시간대
 * @param departedAt             출발 보고 시각
 * @param endTime                예정 종료 시각({@code HH:mm})
 */
public record OutingActiveResponse(
    String code,
    String studentNickname,
    String studentProfileImageUrl,
    String studentRealName,
    Integer studentGrade,
    Integer studentClassNo,
    String reason,
    OutingTimeSlot timeSlot,
    LocalDateTime departedAt,
    String endTime
) {}
