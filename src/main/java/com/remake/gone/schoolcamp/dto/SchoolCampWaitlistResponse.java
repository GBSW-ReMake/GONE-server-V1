package com.remake.gone.schoolcamp.dto;

import java.time.LocalDateTime;

/**
 * 스쿨캠핑 "자리나면 알림받기" 등록 응답.
 *
 * @param month        서버가 계산한 "이번 달"({@code yyyyMM}), 요청 파라미터 아님
 * @param registeredAt 등록(또는 재등록) 시각
 */
public record SchoolCampWaitlistResponse(String month, LocalDateTime registeredAt) {}
