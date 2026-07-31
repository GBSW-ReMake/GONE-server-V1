package com.remake.gone.auth.dto;

import lombok.Builder;

/**
 * 인증번호 불일치 시 에러 응답의 {@code data}에 함께 실어 보내는 실패 정보 DTO.
 *
 * @param currentFailCount 누적 실패 횟수
 * @param maxFailCount     허용되는 최대 실패 횟수
 */
@Builder
public record PhoneVerifyFailResponse(
    long currentFailCount,
    long maxFailCount) {
}
