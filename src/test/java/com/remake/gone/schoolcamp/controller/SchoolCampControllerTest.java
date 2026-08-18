package com.remake.gone.schoolcamp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.schoolcamp.dto.RegisterSchoolCampDatesRequest;
import com.remake.gone.schoolcamp.dto.SchoolCampCalendarResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampSessionResponse;
import com.remake.gone.schoolcamp.enums.SchoolCampStatus;
import com.remake.gone.schoolcamp.service.SchoolCampService;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link SchoolCampController}에 대한 웹 계층(슬라이스) 테스트.
 */
@WebMvcTest(SchoolCampController.class)
@AutoConfigureMockMvc(addFilters = false)
class SchoolCampControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SchoolCampService schoolCampService;

  @Nested
  @DisplayName("POST /api/v1/school-camps")
  class RegisterCampDates {

    @Test
    @DisplayName("campDates가 비어있으면 400을 반환한다")
    void returns400WhenCampDatesEmpty() throws Exception {
      mockMvc.perform(post("/api/v1/school-camps")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"campDates\": []}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("날짜 형식이 yyyyMMdd가 아니면 400을 반환한다")
    void returns400WhenDateFormatInvalid() throws Exception {
      mockMvc.perform(post("/api/v1/school-camps")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"campDates\": [\"2026-04-03\"]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("정상 등록 시 201 Created를 반환한다")
    void returns201OnSuccess() throws Exception {
      given(schoolCampService.registerCampDates(List.of("20260406")))
          .willReturn(List.of(new SchoolCampSessionResponse(12L, "20260406")));

      mockMvc.perform(post("/api/v1/school-camps")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"campDates\": [\"20260406\"]}"))
          .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("요청받은 날짜 목록으로 서비스를 호출하고 결과를 그대로 반환한다")
    void callsServiceWithRequestedDates() {
      SchoolCampController controller = new SchoolCampController(schoolCampService);
      List<SchoolCampSessionResponse> expected = List.of(
          new SchoolCampSessionResponse(12L, "20260403"));
      given(schoolCampService.registerCampDates(List.of("20260403"))).willReturn(expected);

      ApiResponse<List<SchoolCampSessionResponse>> response =
          controller.registerCampDates(new RegisterSchoolCampDatesRequest(List.of("20260403")));

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("GET /api/v1/school-camps")
  class GetCalendar {

    @Test
    @DisplayName("month가 없으면 400을 반환한다")
    void returns400WhenMonthMissing() throws Exception {
      mockMvc.perform(get("/api/v1/school-camps"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("month 형식이 yyyyMM이 아니면 400을 반환한다")
    void returns400WhenMonthFormatInvalid() throws Exception {
      mockMvc.perform(get("/api/v1/school-camps").param("month", "2026"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("파싱된 month로 서비스를 호출하고 결과를 그대로 반환한다")
    void callsServiceWithParsedMonth() {
      SchoolCampController controller = new SchoolCampController(schoolCampService);
      List<SchoolCampCalendarResponse> expected = List.of(
          new SchoolCampCalendarResponse(12L, "20260403", SchoolCampStatus.OPEN, null, null));
      given(schoolCampService.getCalendar(YearMonth.of(2026, 4))).willReturn(expected);

      ApiResponse<List<SchoolCampCalendarResponse>> response =
          controller.getCalendar(YearMonth.of(2026, 4));

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
      verify(schoolCampService).getCalendar(YearMonth.of(2026, 4));
    }
  }
}
