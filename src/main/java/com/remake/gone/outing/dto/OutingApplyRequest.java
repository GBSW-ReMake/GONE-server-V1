package com.remake.gone.outing.dto;

import com.remake.gone.outing.enums.OutingTimeSlot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 외출증 신청 요청 DTO.
 *
 * @param reason          외출 사유
 * @param outingDate      외출 날짜. {@code yyyyMMdd} 형식(예: {@code "20260814"})
 * @param timeSlot        외출 시간대. {@code CUSTOM}이면 {@code customStartTime}/
 *                        {@code customEndTime}이 필수이고, 그 외 값이면 두 필드는 무시된다
 * @param customStartTime 커스텀 시작 시각. {@code HH:mm} 형식(예: {@code "14:00"})
 * @param customEndTime   커스텀 종료 시각. {@code HH:mm} 형식
 * @param teacherUserId   담당 선생님으로 지정할 사용자 ID
 */
public record OutingApplyRequest(
    @NotBlank
    @Size(max = 200, message = "사유는 200자 이하로 입력해주세요")
    String reason,

    @NotBlank
    @Pattern(regexp = "^\\d{8}$", message = "외출 날짜는 yyyyMMdd 형식으로 입력해주세요")
    String outingDate,

    @NotNull
    OutingTimeSlot timeSlot,

    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "커스텀 시작 시각은 HH:mm 형식으로 입력해주세요")
    String customStartTime,

    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "커스텀 종료 시각은 HH:mm 형식으로 입력해주세요")
    String customEndTime,

    @NotNull
    Long teacherUserId
) {}
