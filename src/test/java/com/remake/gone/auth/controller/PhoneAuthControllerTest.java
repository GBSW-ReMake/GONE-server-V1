package com.remake.gone.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.auth.dto.PhoneSendCodeResponse;
import com.remake.gone.auth.dto.PhoneVerifyCodeResponse;
import com.remake.gone.auth.service.PhoneAuthService;
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
 * {@link PhoneAuthController}에 대한 웹 계층(슬라이스) 테스트.
 *
 * <p>{@link PhoneAuthService}는 가짜로 대체하고, 요청 검증(@Valid)과 정상 응답 형태만 확인한다.
 */
@WebMvcTest(PhoneAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class PhoneAuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PhoneAuthService phoneAuthService;

  @Nested
  @DisplayName("POST /api/v1/auth/phone/send-code")
  class SendCode {

    @Test
    @DisplayName("형식이 올바르면 200과 유효시간을 반환한다")
    void returns200WhenValid() throws Exception {
      given(phoneAuthService.sendVerificationCode(any()))
          .willReturn(new PhoneSendCodeResponse(300));

      mockMvc.perform(post("/api/v1/auth/phone/send-code")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"phoneNumber\": \"01099999999\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.expiresIn").value(300));
    }

    @Test
    @DisplayName("하이픈이 포함된 전화번호 형식이면 400을 반환한다")
    void returns400WhenPhoneNumberHasHyphens() throws Exception {
      mockMvc.perform(post("/api/v1/auth/phone/send-code")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"phoneNumber\": \"010-9999-9999\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("빈 문자열이면 400을 반환한다")
    void returns400WhenBlank() throws Exception {
      mockMvc.perform(post("/api/v1/auth/phone/send-code")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"phoneNumber\": \"\"}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("POST /api/v1/auth/phone/verify-code")
  class VerifyCode {

    @Test
    @DisplayName("형식이 올바르면 200과 ticket을 반환한다")
    void returns200WhenValid() throws Exception {
      given(phoneAuthService.verifyCode(any()))
          .willReturn(new PhoneVerifyCodeResponse("some-ticket", 600));

      mockMvc.perform(post("/api/v1/auth/phone/verify-code")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"phoneNumber\": \"01099999999\", \"code\": \"123456\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.ticket").value("some-ticket"));
    }

    @Test
    @DisplayName("인증번호가 6자리 숫자가 아니면 400을 반환한다")
    void returns400WhenCodeInvalid() throws Exception {
      mockMvc.perform(post("/api/v1/auth/phone/verify-code")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"phoneNumber\": \"01099999999\", \"code\": \"12ab\"}"))
          .andExpect(status().isBadRequest());
    }
  }
}
