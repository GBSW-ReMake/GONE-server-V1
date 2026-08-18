package com.remake.gone.schoolcamp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * 스쿨캠핑 날짜 일괄 등록 요청 DTO.
 *
 * @param campDates 등록할 날짜 목록. 각 값은 {@code yyyyMMdd} 형식(예: {@code "20260403"})
 */
public record RegisterSchoolCampDatesRequest(
    @NotEmpty
    List<
        @NotBlank
        @Pattern(regexp = "^\\d{8}$", message = "날짜는 yyyyMMdd 형식으로 입력해주세요")
        String
    > campDates
) {}
