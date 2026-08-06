package com.remake.gone.outing.dto;

import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;

/**
 * 외출증 신청 응답 DTO.
 *
 * @param id                     외부 식별자 코드(내부 PK가 아니라 프론트에 표시할 코드)
 * @param studentNickname        학생 별명({@code User.name})
 * @param studentProfileImageUrl 학생 프로필 사진 presigned URL. 없으면 {@code null}
 * @param studentRealName        학생 실명({@code Gbsw.name})
 * @param studentGrade           학생 학년
 * @param studentClassNo         학생 반
 * @param teacherName            담당 선생님 실명({@code Gbsw.name})
 * @param reason                 외출 사유
 * @param outingDate             외출 날짜({@code yyyyMMdd})
 * @param timeSlot               외출 시간대
 * @param startTime              시작 시각({@code HH:mm})
 * @param endTime                종료 시각({@code HH:mm})
 * @param status                 외출증 상태
 */
public record OutingResponse(
    String id,
    String studentNickname,
    String studentProfileImageUrl,
    String studentRealName,
    Integer studentGrade,
    Integer studentClassNo,
    String teacherName,
    String reason,
    String outingDate,
    OutingTimeSlot timeSlot,
    String startTime,
    String endTime,
    OutingStatus status
) {}
