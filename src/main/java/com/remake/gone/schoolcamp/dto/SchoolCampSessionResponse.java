package com.remake.gone.schoolcamp.dto;

/**
 * 스쿨캠핑 세션 등록 결과 DTO.
 *
 * @param sessionId 생성된 세션 ID
 * @param campDate  세션 날짜. {@code yyyyMMdd} 형식
 */
public record SchoolCampSessionResponse(Long sessionId, String campDate) {}
