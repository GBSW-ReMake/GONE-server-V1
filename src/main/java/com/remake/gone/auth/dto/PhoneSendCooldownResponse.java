package com.remake.gone.auth.dto;

import lombok.Builder;

/**
 * 인증번호 재발송 쿨다운 위반 시 에러 응답의 {@code data}에 함께 실어 보내는 정보 DTO.
 *
 * @param remainingSeconds 재발송이 가능해지기까지 남은 시간(초)
 */
@Builder
public record PhoneSendCooldownResponse(long remainingSeconds) {
}
