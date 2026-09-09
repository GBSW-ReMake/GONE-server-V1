package com.remake.gone.outing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.outing.dto.OutingActiveResponse;
import com.remake.gone.outing.dto.OutingApplyRequest;
import com.remake.gone.outing.dto.OutingLocationPointResponse;
import com.remake.gone.outing.dto.OutingLocationRequest;
import com.remake.gone.outing.dto.OutingLocationsResponse;
import com.remake.gone.outing.dto.OutingRejectRequest;
import com.remake.gone.outing.dto.OutingResponse;
import com.remake.gone.outing.enums.OutingQueryPeriod;
import com.remake.gone.outing.enums.OutingQueryStatus;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.service.OutingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.PENDING,
          null, null, null, false);
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
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.APPROVED,
          null, null, null, false);
      given(outingService.approveOuting(eq(TEACHER_ID), eq(code), any(LocalDateTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().approveOuting(new UserPrincipal(TEACHER_ID), code);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("PATCH /api/v1/outings/{code}/reject")
  class RejectOuting {

    @Test
    @DisplayName("principal의 userId, code, 거절 사유를 그대로 서비스에 전달한다")
    void passesPrincipalCodeAndReasonToService() {
      String code = "8A1zx9202n";
      String reason = "지금은 상담 시간이라 곤란해요";
      OutingResponse expected = new OutingResponse(
          code, "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.REJECTED,
          reason, null, null, false);
      given(outingService.rejectOuting(
          eq(TEACHER_ID), eq(code), eq(reason), any(LocalDateTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response = controller()
          .rejectOuting(new UserPrincipal(TEACHER_ID), code, new OutingRejectRequest(reason));

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    @DisplayName("rejectedReason이 비어있으면 400을 반환한다")
    void returns400WhenRejectedReasonBlank() throws Exception {
      mockMvc.perform(patch("/api/v1/outings/8A1zx9202n/reject")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"rejectedReason\": \"\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("rejectedReason이 200자를 초과하면 400을 반환한다")
    void returns400WhenRejectedReasonTooLong() throws Exception {
      String tooLong = "가".repeat(201);

      mockMvc.perform(patch("/api/v1/outings/8A1zx9202n/reject")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"rejectedReason\": \"" + tooLong + "\"}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("POST /api/v1/outings/{code}/depart")
  class DepartOuting {

    @Test
    @DisplayName("principal의 userId, code, 좌표를 그대로 서비스에 전달한다")
    void passesPrincipalCodeAndRequestToService() {
      String code = "8A1zx9202n";
      OutingLocationRequest request = new OutingLocationRequest(36.1234, 128.4321);
      OutingResponse expected = new OutingResponse(
          code, "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.DEPARTED,
          null, LocalDateTime.of(2026, 8, 14, 12, 31), null, false);
      given(outingService.departOuting(
          eq(STUDENT_ID), eq(code), eq(request), any(LocalDateTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().departOuting(new UserPrincipal(STUDENT_ID), code, request);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
      assertThat(response.message()).isEqualTo("출발이 기록되었습니다.");
    }

    @Test
    @DisplayName("offSchedule이 true면 안내 메시지로 분기한다")
    void switchesMessageWhenOffSchedule() {
      String code = "8A1zx9202n";
      OutingLocationRequest request = new OutingLocationRequest(36.1234, 128.4321);
      OutingResponse expected = new OutingResponse(
          code, "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.DEPARTED,
          null, LocalDateTime.of(2026, 8, 14, 14, 10), null, true);
      given(outingService.departOuting(
          eq(STUDENT_ID), eq(code), eq(request), any(LocalDateTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().departOuting(new UserPrincipal(STUDENT_ID), code, request);

      assertThat(response.message()).isEqualTo("예정된 시간 외에 출발이 기록되었습니다.");
    }

    @Test
    @DisplayName("latitude가 없으면 400을 반환한다")
    void returns400WhenLatitudeMissing() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/depart")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"longitude\": 128.4321}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("longitude가 없으면 400을 반환한다")
    void returns400WhenLongitudeMissing() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/depart")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"latitude\": 36.1234}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("latitude가 범위(-90~90) 밖이면 400을 반환한다(#43 코드 리뷰 Low 3번 대응)")
    void returns400WhenLatitudeOutOfRange() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/depart")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"latitude\": 999.0, \"longitude\": 128.4321}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("longitude가 범위(-180~180) 밖이면 400을 반환한다(#43 코드 리뷰 Low 3번 대응)")
    void returns400WhenLongitudeOutOfRange() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/depart")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"latitude\": 36.1234, \"longitude\": 181.0}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("POST /api/v1/outings/{code}/return")
  class ReturnOuting {

    @Test
    @DisplayName("principal의 userId, code, 좌표를 그대로 서비스에 전달한다")
    void passesPrincipalCodeAndRequestToService() {
      String code = "8A1zx9202n";
      OutingLocationRequest request = new OutingLocationRequest(36.1234, 128.4321);
      OutingResponse expected = new OutingResponse(
          code, "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.RETURNED,
          null, LocalDateTime.of(2026, 8, 14, 12, 31),
          LocalDateTime.of(2026, 8, 14, 13, 35), false);
      given(outingService.returnOuting(
          eq(STUDENT_ID), eq(code), eq(request), any(LocalDateTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().returnOuting(new UserPrincipal(STUDENT_ID), code, request);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
      assertThat(response.message()).isEqualTo("도착이 기록되었습니다.");
    }

    @Test
    @DisplayName("offSchedule이 true면 안내 메시지로 분기한다")
    void switchesMessageWhenOffSchedule() {
      String code = "8A1zx9202n";
      OutingLocationRequest request = new OutingLocationRequest(36.1234, 128.4321);
      OutingResponse expected = new OutingResponse(
          code, "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.RETURNED,
          null, LocalDateTime.of(2026, 8, 14, 12, 31),
          LocalDateTime.of(2026, 8, 14, 19, 0), true);
      given(outingService.returnOuting(
          eq(STUDENT_ID), eq(code), eq(request), any(LocalDateTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().returnOuting(new UserPrincipal(STUDENT_ID), code, request);

      assertThat(response.message()).isEqualTo("예정된 시간 외에 도착이 기록되었습니다.");
    }

    @Test
    @DisplayName("latitude가 없으면 400을 반환한다")
    void returns400WhenLatitudeMissing() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/return")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"longitude\": 128.4321}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("GET /api/v1/outings/me/requests")
  class GetMyRequests {

    @Test
    @DisplayName("principal의 userId와 쿼리 파라미터를 그대로 서비스에 전달한다")
    void passesPrincipalAndParamsToService() {
      given(outingService.getMyRequests(
          eq(STUDENT_ID), eq(OutingQueryPeriod.THIS_WEEK), isNull(), isNull(), isNull(),
          eq(0), eq(20), any(LocalDate.class), any(LocalTime.class)))
          .willReturn(PageResponse.of(List.of(), 0, 20));

      ApiResponse<PageResponse<OutingResponse>> response = controller().getMyRequests(
          new UserPrincipal(STUDENT_ID), OutingQueryPeriod.THIS_WEEK, null, null, null, 0, 20);

      assertThat(response.success()).isTrue();
      assertThat(response.data().content()).isEmpty();
    }

    @Test
    @DisplayName("period를 생략하면 THIS_WEEK가 기본값으로 적용된다")
    void defaultsPeriodToThisWeek() throws Exception {
      given(outingService.getMyRequests(
          eq(STUDENT_ID), eq(OutingQueryPeriod.THIS_WEEK), isNull(), isNull(), isNull(),
          eq(0), eq(20), any(LocalDate.class), any(LocalTime.class)))
          .willReturn(PageResponse.of(List.of(), 0, 20));

      mockMvc.perform(get("/api/v1/outings/me/requests"))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("dateFrom 형식이 yyyyMMdd가 아니면 400을 반환한다")
    void returns400WhenDateFromFormatInvalid() throws Exception {
      mockMvc.perform(get("/api/v1/outings/me/requests").param("dateFrom", "not-a-date"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("period 값이 정의되지 않은 값이면 400을 반환한다")
    void returns400WhenPeriodInvalid() throws Exception {
      mockMvc.perform(get("/api/v1/outings/me/requests").param("period", "NOT_A_PERIOD"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("status 값이 정의되지 않은 값이면 400을 반환한다")
    void returns400WhenStatusInvalid() throws Exception {
      mockMvc.perform(get("/api/v1/outings/me/requests").param("status", "NOT_A_STATUS"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("GET /api/v1/outings/me/received")
  class GetReceivedOutings {

    @Test
    @DisplayName("principal의 userId와 쿼리 파라미터를 그대로 서비스에 전달한다")
    void passesPrincipalAndParamsToService() {
      given(outingService.getReceivedOutings(
          eq(TEACHER_ID), eq(OutingQueryPeriod.THIS_WEEK), isNull(), isNull(), isNull(),
          eq(0), eq(20), any(LocalDate.class), any(LocalTime.class)))
          .willReturn(PageResponse.of(List.of(), 0, 20));

      ApiResponse<PageResponse<OutingResponse>> response = controller().getReceivedOutings(
          new UserPrincipal(TEACHER_ID), OutingQueryPeriod.THIS_WEEK, null, null, null, 0, 20);

      assertThat(response.success()).isTrue();
      assertThat(response.data().content()).isEmpty();
    }
  }

  @Nested
  @DisplayName("GET /api/v1/outings/active")
  class GetActiveOutings {

    @Test
    @DisplayName("쿼리 파라미터를 그대로 서비스에 전달한다")
    void passesParamsToService() {
      OutingActiveResponse expected = new OutingActiveResponse(
          "8A1zx9202n", "길동이", null, "홍길동", 3, 4,
          "치과 진료", OutingTimeSlot.LUNCH, LocalDateTime.of(2026, 8, 14, 12, 31), "13:40");
      given(outingService.getActiveOutings(0, 20))
          .willReturn(PageResponse.of(List.of(expected), 0, 20));

      ApiResponse<PageResponse<OutingActiveResponse>> response =
          controller().getActiveOutings(0, 20);

      assertThat(response.success()).isTrue();
      assertThat(response.data().content()).containsExactly(expected);
    }

    @Test
    @DisplayName("page/size를 생략하면 기본값(0, 20)이 적용된다")
    void defaultsPageAndSize() throws Exception {
      given(outingService.getActiveOutings(0, 20))
          .willReturn(PageResponse.of(List.of(), 0, 20));

      mockMvc.perform(get("/api/v1/outings/active"))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("GET /api/v1/outings")
  class GetDailyOverview {

    @Test
    @DisplayName("쿼리 파라미터를 그대로 서비스에 전달한다")
    void passesParamsToService() {
      given(outingService.getDailyOverview(
          eq(LocalDate.of(2026, 8, 14)), eq(OutingQueryStatus.PENDING), eq(0), eq(20),
          any(LocalDate.class), any(LocalTime.class)))
          .willReturn(PageResponse.of(List.of(), 0, 20));

      ApiResponse<PageResponse<OutingResponse>> response = controller().getDailyOverview(
          LocalDate.of(2026, 8, 14), OutingQueryStatus.PENDING, 0, 20);

      assertThat(response.success()).isTrue();
      assertThat(response.data().content()).isEmpty();
    }

    @Test
    @DisplayName("date/status/page/size를 생략하면 기본값이 적용된다")
    void defaultsParams() throws Exception {
      given(outingService.getDailyOverview(
          isNull(), isNull(), eq(0), eq(20), any(LocalDate.class), any(LocalTime.class)))
          .willReturn(PageResponse.of(List.of(), 0, 20));

      mockMvc.perform(get("/api/v1/outings"))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("date 형식이 yyyyMMdd가 아니면 400을 반환한다")
    void returns400WhenDateFormatInvalid() throws Exception {
      mockMvc.perform(get("/api/v1/outings").param("date", "not-a-date"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("status 값이 정의되지 않은 값이면 400을 반환한다")
    void returns400WhenStatusInvalid() throws Exception {
      mockMvc.perform(get("/api/v1/outings").param("status", "NOT_A_STATUS"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("GET /api/v1/outings/{code}")
  class GetOutingDetail {

    @Test
    @DisplayName("principal의 userId와 code를 그대로 서비스에 전달한다")
    void passesPrincipalAndCodeToService() {
      String code = "8A1zx9202n";
      OutingResponse expected = new OutingResponse(
          code, "길동이", null, "홍길동", 3, 4, "김선생",
          "치과 진료", "20260814", OutingTimeSlot.LUNCH, "12:30", "13:40", OutingStatus.PENDING,
          null, null, null, false);
      given(outingService.getOutingDetail(
          eq(STUDENT_ID), eq(code), any(LocalDate.class), any(LocalTime.class)))
          .willReturn(expected);

      ApiResponse<OutingResponse> response =
          controller().getOutingDetail(new UserPrincipal(STUDENT_ID), code);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("POST /api/v1/outings/{code}/locations")
  class RecordLocationPing {

    @Test
    @DisplayName("principal의 userId, code, 좌표를 그대로 서비스에 전달한다")
    void passesPrincipalCodeAndRequestToService() {
      String code = "8A1zx9202n";
      OutingLocationRequest request = new OutingLocationRequest(36.1234, 128.4321);

      ApiResponse<Void> response =
          controller().recordLocationPing(new UserPrincipal(STUDENT_ID), code, request);

      verify(outingService).recordLocationPing(
          eq(STUDENT_ID), eq(code), eq(request), any(LocalDateTime.class));
      assertThat(response.success()).isTrue();
      assertThat(response.message()).isEqualTo("위치가 저장되었습니다.");
    }

    @Test
    @DisplayName("latitude가 없으면 400을 반환한다")
    void returns400WhenLatitudeMissing() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/locations")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"longitude\": 128.4321}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("latitude가 범위(-90~90) 밖이면 400을 반환한다")
    void returns400WhenLatitudeOutOfRange() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/locations")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"latitude\": 999.0, \"longitude\": 128.4321}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("longitude가 범위(-180~180) 밖이면 400을 반환한다")
    void returns400WhenLongitudeOutOfRange() throws Exception {
      mockMvc.perform(post("/api/v1/outings/8A1zx9202n/locations")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"latitude\": 36.1234, \"longitude\": 181.0}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("GET /api/v1/outings/{code}/locations")
  class GetOutingLocations {

    @Test
    @DisplayName("principal의 userId와 code를 그대로 서비스에 전달한다")
    void passesPrincipalAndCodeToService() {
      String code = "8A1zx9202n";
      OutingLocationsResponse expected = new OutingLocationsResponse(
          code, OutingStatus.DEPARTED,
          List.of(new OutingLocationPointResponse(
              36.1234, 128.4321, LocalDateTime.of(2026, 8, 14, 12, 31))));
      given(outingService.getOutingLocations(TEACHER_ID, code)).willReturn(expected);

      ApiResponse<OutingLocationsResponse> response =
          controller().getOutingLocations(new UserPrincipal(TEACHER_ID), code);

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }
}
