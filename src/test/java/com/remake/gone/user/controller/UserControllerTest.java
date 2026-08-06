package com.remake.gone.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.user.dto.MyProfileResponse;
import com.remake.gone.user.dto.UpdateNameRequest;
import com.remake.gone.user.dto.UserSearchResponse;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.service.UserService;
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
 * {@link UserController}에 대한 웹 계층(슬라이스) 테스트.
 *
 * <p>이 프로젝트의 {@code @WebMvcTest} 슬라이스는 Spring Security 필터 체인이 MockMvc에 실제로
 * 붙지 않아 {@code @AuthenticationPrincipal} 주입을 검증할 수 없다({@code AuthControllerTest}의
 * 로그아웃 테스트와 같은 이유). 그래서 요청 검증(Bean Validation) 테스트는 MockMvc로, principal이
 * 실제로 서비스에 전달되는지는 컨트롤러를 직접 호출해서 검증한다.
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserService userService;

  private static final Long USER_ID = 1L;

  @Nested
  @DisplayName("GET /api/v1/users/me")
  class GetMyProfile {

    @Test
    @DisplayName("인증된 사용자의 userId로 프로필을 조회한다")
    void callsServiceWithAuthenticatedUserId() {
      UserController controller = new UserController(userService);
      MyProfileResponse expected =
          new MyProfileResponse("3118정문경", false, null, "김정문", 3, 1);
      given(userService.getMyProfile(USER_ID)).willReturn(expected);

      ApiResponse<MyProfileResponse> response =
          controller.getMyProfile(new UserPrincipal(USER_ID));

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("GET /api/v1/users/search")
  class Search {

    @Test
    @DisplayName("query가 비어있으면 400을 반환한다")
    void returns400WhenQueryBlank() throws Exception {
      mockMvc.perform(get("/api/v1/users/search").param("query", ""))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("query가 없으면 400을 반환한다")
    void returns400WhenQueryMissing() throws Exception {
      mockMvc.perform(get("/api/v1/users/search"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검색어로 서비스를 호출하고 결과를 그대로 반환한다")
    void callsServiceWithQuery() {
      UserController controller = new UserController(userService);
      List<UserSearchResponse> expected =
          List.of(new UserSearchResponse(55L, "영희", "이영희", 3, 1));
      given(userService.search("영희")).willReturn(expected);

      ApiResponse<List<UserSearchResponse>> response = controller.search("영희");

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("PATCH /api/v1/users/me/name")
  class ChangeName {

    @Test
    @DisplayName("이름이 비어있으면 400을 반환하고 서비스는 호출되지 않는다")
    void returns400WhenNameBlank() throws Exception {
      mockMvc.perform(patch("/api/v1/users/me/name")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\": \"\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이름이 20자를 초과하면 400을 반환한다")
    void returns400WhenNameTooLong() throws Exception {
      String body = "{\"name\": \"%s\"}".formatted("가".repeat(21));

      mockMvc.perform(patch("/api/v1/users/me/name")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증된 사용자의 userId로 이름 변경을 요청한다")
    void callsServiceWithAuthenticatedUserId() {
      UserController controller = new UserController(userService);

      ApiResponse<Void> response =
          controller.changeName(new UserPrincipal(USER_ID), new UpdateNameRequest("새이름"));

      assertThat(response.success()).isTrue();
      verify(userService).changeName(USER_ID, "새이름");
    }

    @Test
    @DisplayName("서비스가 중복 예외를 던지면 그대로 전파한다")
    void propagatesConflictFromService() {
      UserController controller = new UserController(userService);
      willThrow(new CustomException(UserErrorCode.NAME_ALREADY_EXISTS))
          .given(userService).changeName(any(), any());

      assertThatThrownBy(() ->
              controller.changeName(new UserPrincipal(USER_ID), new UpdateNameRequest("남의이름")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(UserErrorCode.NAME_ALREADY_EXISTS);
    }
  }
}
