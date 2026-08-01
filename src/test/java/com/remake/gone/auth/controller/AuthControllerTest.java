package com.remake.gone.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.auth.service.AuthService;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.user.exception.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link AuthController}에 대한 웹 계층(슬라이스) 테스트.
 *
 * <p>{@link AuthService}는 가짜로 대체하고, "요청 -> Bean Validation -> 컨트롤러 -> 응답"
 * 흐름만 검증한다. 서버 전체를 띄우지 않아 DB/Redis가 필요 없다.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false) // Spring Security 필터를 끄고 컨트롤러 로직만 본다
class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AuthService authService;

  @Nested
  @DisplayName("POST /api/v1/auth/signup")
  class SignUp {

    private static final String VALID_BODY = """
        {
          "loginId": "testuser01",
          "password": "Test1234!",
          "name": "테스트유저",
          "phoneNumber": "01099999999",
          "ticket": "some-ticket"
        }
        """;

    @Test
    @DisplayName("형식이 올바르면 200과 성공 메시지를 반환한다")
    void returns200WhenValid() throws Exception {
      mockMvc.perform(post("/api/v1/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(VALID_BODY))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("loginId가 4자 미만이면 400을 반환하고 서비스는 호출되지 않는다")
    void returns400WhenLoginIdTooShort() throws Exception {
      String body = VALID_BODY.replace("\"testuser01\"", "\"a\"");

      mockMvc.perform(post("/api/v1/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value("COMMON_001"));

      verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("비밀번호에 특수문자가 없으면 400을 반환한다")
    void returns400WhenPasswordMissingSpecialChar() throws Exception {
      String body = VALID_BODY.replace("Test1234!", "Test12345");

      mockMvc.perform(post("/api/v1/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("서비스가 CustomException을 던지면 그 에러코드의 상태/코드로 응답한다")
    void returnsErrorCodeStatusWhenServiceThrows() throws Exception {
      willThrow(new CustomException(UserErrorCode.LOGIN_ID_ALREADY_EXISTS))
          .given(authService).signUp(any());

      mockMvc.perform(post("/api/v1/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(VALID_BODY))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("USER_002"));
    }

    @Test
    @DisplayName("동시 요청 경합으로 DB unique 제약을 위반하면 500이 아니라 409로 응답한다")
    void returnsConflictOnRaceCondition() throws Exception {
      willThrow(new DataIntegrityViolationException("duplicate"))
          .given(authService).signUp(any());

      mockMvc.perform(post("/api/v1/auth/signup")
              .contentType(MediaType.APPLICATION_JSON)
              .content(VALID_BODY))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("COMMON_006"));
    }
  }

  @Nested
  @DisplayName("GET /api/v1/auth/login-id/check")
  class CheckLoginId {

    @Test
    @DisplayName("사용 가능하면 available:true를 반환한다")
    void returnsAvailableTrue() throws Exception {
      given(authService.isLoginIdAvailable("freeuser")).willReturn(true);

      mockMvc.perform(get("/api/v1/auth/login-id/check").param("loginId", "freeuser"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    @DisplayName("이미 사용 중이면 available:false를 반환한다")
    void returnsAvailableFalse() throws Exception {
      given(authService.isLoginIdAvailable("taken")).willReturn(false);

      mockMvc.perform(get("/api/v1/auth/login-id/check").param("loginId", "taken"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    @DisplayName("한글 등 영문/숫자가 아닌 문자가 포함되면 400을 반환한다")
    void returns400WhenInvalidCharacters() throws Exception {
      mockMvc.perform(get("/api/v1/auth/login-id/check").param("loginId", "한글아이디"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("3자 이하면 400을 반환한다")
    void returns400WhenTooShort() throws Exception {
      mockMvc.perform(get("/api/v1/auth/login-id/check").param("loginId", "abc"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("GET /api/v1/auth/name/check")
  class CheckName {

    @Test
    @DisplayName("사용 가능하면 available:true를 반환한다")
    void returnsAvailableTrue() throws Exception {
      given(authService.isNameAvailable("새싹")).willReturn(true);

      mockMvc.perform(get("/api/v1/auth/name/check").param("name", "새싹"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    @DisplayName("21자 이상이면 400을 반환한다")
    void returns400WhenTooLong() throws Exception {
      mockMvc.perform(get("/api/v1/auth/name/check").param("name", "가".repeat(21)))
          .andExpect(status().isBadRequest());
    }
  }
}
