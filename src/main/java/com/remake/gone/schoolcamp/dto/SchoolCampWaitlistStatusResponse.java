package com.remake.gone.schoolcamp.dto;

import java.time.LocalDateTime;

/**
 * 스쿨캠핑 "자리나면 알림받기" 이번 달 등록 상태 조회 응답.
 *
 * @param registered   이번 달 유효한 대기 등록이 있는지 여부
 * @param registeredAt 등록 시각. {@code registered == false}면 {@code null}
 */
public record SchoolCampWaitlistStatusResponse(boolean registered, LocalDateTime registeredAt) {}
