package com.remake.gone.outing.dto;

import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import java.time.LocalDateTime;

/**
 * 외출증 신청 응답 DTO.
 *
 * @param code                   외부 식별자 코드(내부 PK가 아니라 프론트에 표시할 코드)
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
 * @param rejectedReason         거절 사유. {@code REJECTED} 상태가 아니면 {@code null}
 * @param departedAt             출발 보고 시각(#43). 아직 출발 전이면 {@code null}
 * @param returnedAt             도착 보고 시각(#43). 아직 도착 전이면 {@code null}
 * @param offSchedule            출발/도착이 이 외출증의 예정 시간대({@code startTime}~
 *                               {@code endTime}) 밖에서 보고됐는지(#43). 출발/도착 전이면
 *                               {@code false}
 */
public record OutingResponse(
    String code,
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
    OutingStatus status,
    String rejectedReason,
    LocalDateTime departedAt,
    LocalDateTime returnedAt,
    boolean offSchedule
) {}
