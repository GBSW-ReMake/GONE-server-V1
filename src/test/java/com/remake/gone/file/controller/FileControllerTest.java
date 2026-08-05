package com.remake.gone.file.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.file.dto.ImageUploadUrlRequest;
import com.remake.gone.file.dto.ImageUploadUrlResponse;
import com.remake.gone.file.dto.ProfileImageConfirmRequest;
import com.remake.gone.file.exception.FileErrorCode;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.user.service.UserService;
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
 * {@link FileController}에 대한 웹 계층(슬라이스) 테스트.
 *
 * <p>이 프로젝트의 {@code @WebMvcTest} 슬라이스는 Spring Security 필터 체인이 MockMvc에 실제로
 * 붙지 않아 {@code @AuthenticationPrincipal} 주입을 검증할 수 없다({@code AuthControllerTest}의
 * 로그아웃 테스트와 같은 이유). 그래서 요청 검증(Bean Validation)은 MockMvc로, principal이
 * 관련된 분기 로직(keyPrefix, 소유권 확인)은 컨트롤러를 직접 호출해서 검증한다.
 */
@WebMvcTest(FileController.class)
@AutoConfigureMockMvc(addFilters = false)
class FileControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private R2FileService r2FileService;

  @MockitoBean
  private UserService userService;

  private static final Long USER_ID = 1L;

  private FileController controller() {
    return new FileController(r2FileService, userService);
  }

  @Nested
  @DisplayName("POST /api/v1/files/profile-image/upload-url")
  class GetProfileImageUploadUrl {

    @Test
    @DisplayName("본인 userId 기반 keyPrefix로 업로드 URL을 발급한다")
    void generatesUploadUrlWithUserKeyPrefix() {
      ImageUploadUrlResponse expected =
          new ImageUploadUrlResponse("https://r2.example/upload", "profile/1/abc.jpg");
      given(r2FileService.generateUploadUrl(
          eq("profile/1"), eq("photo.jpg"), eq("image/jpeg"), eq(204_800L)))
          .willReturn(expected);

      ApiResponse<ImageUploadUrlResponse> response = controller().getProfileImageUploadUrl(
          new UserPrincipal(USER_ID),
          new ImageUploadUrlRequest("photo.jpg", "image/jpeg", 204_800L)
      );

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    @DisplayName("fileName이 비어있으면 400을 반환하고 서비스는 호출되지 않는다")
    void returns400WhenFileNameBlank() throws Exception {
      mockMvc.perform(post("/api/v1/files/profile-image/upload-url")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fileName\": \"\", \"contentType\": \"image/jpeg\", \"fileSize\": 100}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("contentType이 비어있으면 400을 반환한다")
    void returns400WhenContentTypeBlank() throws Exception {
      mockMvc.perform(post("/api/v1/files/profile-image/upload-url")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fileName\": \"a.jpg\", \"contentType\": \"\", \"fileSize\": 100}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("fileSize가 0 이하면 400을 반환한다")
    void returns400WhenFileSizeNotPositive() throws Exception {
      String body = "{\"fileName\": \"a.jpg\", \"contentType\": \"image/jpeg\", \"fileSize\": 0}";

      mockMvc.perform(post("/api/v1/files/profile-image/upload-url")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("POST /api/v1/files/profile-image/confirm")
  class ConfirmProfileImageUpload {

    @Test
    @DisplayName("R2에 객체가 존재하면 본인 프로필 사진으로 저장한다")
    void savesProfileImageWhenObjectExists() {
      given(r2FileService.checkObjectExists("profile/1/abc.jpg")).willReturn(true);

      ApiResponse<Void> response = controller().confirmProfileImageUpload(
          new UserPrincipal(USER_ID), new ProfileImageConfirmRequest("profile/1/abc.jpg"));

      assertThat(response.success()).isTrue();
      verify(userService).updateProfileImage(USER_ID, "profile/1/abc.jpg");
    }

    @Test
    @DisplayName("R2에 객체가 없으면 거부하고 저장하지 않는다")
    void throwsWhenObjectMissing() {
      given(r2FileService.checkObjectExists("profile/1/missing.jpg")).willReturn(false);

      assertThatThrownBy(() -> controller().confirmProfileImageUpload(
          new UserPrincipal(USER_ID), new ProfileImageConfirmRequest("profile/1/missing.jpg")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(FileErrorCode.UPLOAD_CONFIRM_FAILED);

      verify(userService, never()).updateProfileImage(any(), any());
    }

    @Test
    @DisplayName("다른 사용자 소유의 key면 R2 존재 여부와 무관하게 거부한다")
    void throwsWhenKeyOwnedByAnotherUser() {
      assertThatThrownBy(() -> controller().confirmProfileImageUpload(
          new UserPrincipal(USER_ID), new ProfileImageConfirmRequest("profile/2/other.jpg")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(FileErrorCode.UPLOAD_CONFIRM_FAILED);

      verify(r2FileService, never()).checkObjectExists(any());
      verify(userService, never()).updateProfileImage(any(), any());
    }

    @Test
    @DisplayName("userId 접두사 매칭은 문자열 접두사가 아니라 경로 구분자 기준이어야 한다")
    void rejectsKeyWhosePrefixOnlyPartiallyMatches() {
      // userId=1의 keyPrefix("profile/1")는 userId=12의 key("profile/12/...")와
      // 문자열로는 접두사가 겹치므로, "/"까지 포함해 비교하지 않으면 잘못 통과할 수 있다.
      assertThatThrownBy(() -> controller().confirmProfileImageUpload(
          new UserPrincipal(USER_ID), new ProfileImageConfirmRequest("profile/12/other.jpg")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(FileErrorCode.UPLOAD_CONFIRM_FAILED);

      verify(r2FileService, never()).checkObjectExists(any());
    }

    @Test
    @DisplayName("key가 비어있으면 400을 반환한다")
    void returns400WhenKeyBlank() throws Exception {
      mockMvc.perform(post("/api/v1/files/profile-image/confirm")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"key\": \"\"}"))
          .andExpect(status().isBadRequest());
    }
  }
}
