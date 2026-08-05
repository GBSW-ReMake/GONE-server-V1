package com.remake.gone.timetable.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.timetable.dto.PeriodResponse;
import com.remake.gone.timetable.dto.TimetableResponse;
import com.remake.gone.timetable.service.TimetableService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link TimetableController}에 대한 웹 계층(슬라이스) 테스트.
 *
 * <p>이 프로젝트의 {@code @WebMvcTest} 슬라이스는 Spring Security 필터 체인이 MockMvc에 실제로
 * 붙지 않아 {@code @AuthenticationPrincipal} 주입을 검증할 수 없다({@code UserControllerTest}와
 * 같은 이유). principal이 서비스에 그대로 전달되는지는 컨트롤러를 직접 호출해서 검증한다.
 */
@WebMvcTest(TimetableController.class)
@AutoConfigureMockMvc(addFilters = false)
class TimetableControllerTest {

  @MockitoBean
  private TimetableService timetableService;

  private static final Long USER_ID = 1L;

  @Nested
  @DisplayName("GET /api/v1/timetables")
  class GetMyTimetable {

    @Test
    @DisplayName("인증된 사용자의 userId와 날짜로 본인 시간표를 조회한다")
    void callsServiceWithAuthenticatedUserIdAndDate() {
      TimetableController controller = new TimetableController(timetableService);
      LocalDate date = LocalDate.of(2026, 3, 23);
      TimetableResponse expected =
          new TimetableResponse("20260323", 3, "3", List.of(new PeriodResponse(1, "자율활동")));
      given(timetableService.getMyTimetable(USER_ID, date)).willReturn(expected);

      ApiResponse<TimetableResponse> response =
          controller.getMyTimetable(new UserPrincipal(USER_ID), date);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
      verify(timetableService).getMyTimetable(USER_ID, date);
    }
  }
}
