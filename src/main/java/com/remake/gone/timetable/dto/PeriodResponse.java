package com.remake.gone.timetable.dto;

/**
 * 시간표 한 교시의 응답.
 *
 * @param period  교시
 * @param subject 과목명(수업내용). NEIS 원본에 {@code *} 접두사가 붙는 경우가 있는데 의미가
 *                불명확해 그대로 내려준다(기획서 참고)
 */
public record PeriodResponse(int period, String subject) {}
