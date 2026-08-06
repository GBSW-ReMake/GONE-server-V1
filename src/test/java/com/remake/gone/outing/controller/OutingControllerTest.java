package com.remake.gone.outing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.service.OutingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * {@link OutingController}에 대한 웹 계층(슬라이스) 테스트.
 *
 * <p>이 프로젝트의 {@code @WebMvcTest} 슬라이스는 Spring Security 필터 체인이 MockMvc에 실제로
 * 붙지 않아 {@code @AuthenticationPrincipal} 주입을 검증할 수 없다({@code FileControllerTest}와
 * 같은 이유). 요청 검증(Bean Validation)은 MockMvc로, principal이 관련된 로직은 컨트롤러를 직접
 * 호출해서 검증한다.
 */
@WebMvcTest(OutingController.class)
@AutoConfigureMockMvc(addFilters = false)
class OutingControllerTest {

  private static final Long STUDENT_ID = 1L;
  private static final Long TEACHER_ID = 42L;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private OutingService outingService;

  private OutingController controller() {
    return new OutingController(outingService);
  }

  @Nested
  @DisplayName("POST /api/v1/outings")
  class ApplyOuting {

    @Test
    @DisplayName("principal의 userId와 요청을 그대로 서비스에 전달한다")
    void passesPrincipalAndRequestToService() {
      OutingApplyRequest request =
          new OutingApplyRequest("치과 진료", "20260814", OutingTimeSlot.LUNCH, null, null, 42L);
      OutingResponse expected = new OutingResponse(
          "8A1zx9202n", "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.PENDING);
      given(outingService.applyOuting(
          eq(STUDENT_ID), eq(request), any(LocalDate.class), any(LocalTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().applyOuting(new UserPrincipal(STUDENT_ID), request);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    @DisplayName("reason이 비어있으면 400을 반환한다")
    void returns400WhenReasonBlank() throws Exception {
      String body = "{\"reason\": \"\", \"outingDate\": \"20260814\", "
          + "\"timeSlot\": \"LUNCH\", \"teacherUserId\": 42}";

      mockMvc.perform(post("/api/v1/outings")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("outingDate 형식이 yyyyMMdd가 아니면 400을 반환한다")
    void returns400WhenOutingDateFormatInvalid() throws Exception {
      String body = "{\"reason\": \"치과 진료\", \"outingDate\": \"2026-08-14\", "
          + "\"timeSlot\": \"LUNCH\", \"teacherUserId\": 42}";

      mockMvc.perform(post("/api/v1/outings")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("timeSlot이 없으면 400을 반환한다")
    void returns400WhenTimeSlotMissing() throws Exception {
      String body = "{\"reason\": \"치과 진료\", \"outingDate\": \"20260814\", "
          + "\"teacherUserId\": 42}";

      mockMvc.perform(post("/api/v1/outings")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("teacherUserId가 없으면 400을 반환한다")
    void returns400WhenTeacherUserIdMissing() throws Exception {
      String body = "{\"reason\": \"치과 진료\", \"outingDate\": \"20260814\", "
          + "\"timeSlot\": \"LUNCH\"}";

      mockMvc.perform(post("/api/v1/outings")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("customStartTime 형식이 HH:mm이 아니면 400을 반환한다")
    void returns400WhenCustomStartTimeFormatInvalid() throws Exception {
      String body = "{\"reason\": \"치과 진료\", \"outingDate\": \"20260814\", "
          + "\"timeSlot\": \"CUSTOM\", \"customStartTime\": \"2pm\", "
          + "\"customEndTime\": \"16:00\", \"teacherUserId\": 42}";

      mockMvc.perform(post("/api/v1/outings")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("PATCH /api/v1/outings/{code}/approve")
  class ApproveOuting {

    @Test
    @DisplayName("principal의 userId와 code를 그대로 서비스에 전달한다")
    void passesPrincipalAndCodeToService() {
      String code = "8A1zx9202n";
      OutingResponse expected = new OutingResponse(
          code, "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.APPROVED);
      given(outingService.approveOuting(eq(TEACHER_ID), eq(code), any(LocalDateTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().approveOuting(new UserPrincipal(TEACHER_ID), code);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }
}
