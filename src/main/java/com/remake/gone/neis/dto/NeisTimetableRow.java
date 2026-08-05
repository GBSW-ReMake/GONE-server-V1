package com.remake.gone.neis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NEIS {@code hisTimetable} 응답의 {@code row} 한 건을 그대로 옮겨 담는 원본 DTO.
 *
 * <p>필드명은 NEIS 응답 그대로(대문자 스네이크 표기)를 {@link JsonProperty}로 매핑한다.
 *
 * @param period  교시
 * @param subject 수업내용(과목명). {@code *} 접두사가 붙는 경우가 있는데 의미가 불명확해 원본
 *                그대로 둔다(기획서 참고).
 */
public record NeisTimetableRow(
    @JsonProperty("PERIO") String period,
    @JsonProperty("ITRT_CNTNT") String subject
) {}
