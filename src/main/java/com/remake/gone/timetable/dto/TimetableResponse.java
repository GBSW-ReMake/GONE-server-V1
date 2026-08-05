package com.remake.gone.timetable.dto;

import java.util.List;

/**
 * 특정 날짜/학급의 시간표 조회 응답.
 *
 * @param date    조회한 날짜(yyyyMMdd)
 * @param grade   학년
 * @param classNm 학교 전체 기준 반 번호(1~4)
 * @param periods 그날의 교시별 시간표. 데이터가 없는 날(공강/방학 등)은 빈 리스트
 */
public record TimetableResponse(
    String date, int grade, String classNm, List<PeriodResponse> periods) {}
