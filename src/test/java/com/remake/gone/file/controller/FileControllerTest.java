package com.remake.gone.file.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link FileController}에 대한 단위 테스트.
 *
 * <p>이 프로젝트의 {@code @WebMvcTest} 슬라이스는 {@code @AuthenticationPrincipal}을 실제로
 * 채워주지 못하므로({@code AuthControllerTest}의 로그아웃 테스트와 같은 이유), 컨트롤러를
 * 직접 호출해서 principal 전달과 분기 로직을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

  @Mock
  private R2FileService r2FileService;

  @Mock
  private UserService userService;

  @InjectMocks
  private FileController fileController;

  private static final Long USER_ID = 1L;

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

      ApiResponse<ImageUploadUrlResponse> response = fileController.getProfileImageUploadUrl(
          new UserPrincipal(USER_ID),
          new ImageUploadUrlRequest("photo.jpg", "image/jpeg", 204_800L)
      );

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("POST /api/v1/files/profile-image/confirm")
  class ConfirmProfileImageUpload {

    @Test
    @DisplayName("R2에 객체가 존재하면 본인 프로필 사진으로 저장한다")
    void savesProfileImageWhenObjectExists() {
      given(r2FileService.checkObjectExists("profile/1/abc.jpg")).willReturn(true);

      ApiResponse<Void> response = fileController.confirmProfileImageUpload(
          new UserPrincipal(USER_ID), new ProfileImageConfirmRequest("profile/1/abc.jpg"));

      assertThat(response.success()).isTrue();
      verify(userService).updateProfileImage(USER_ID, "profile/1/abc.jpg");
    }

    @Test
    @DisplayName("R2에 객체가 없으면 거부하고 저장하지 않는다")
    void throwsWhenObjectMissing() {
      given(r2FileService.checkObjectExists("profile/1/missing.jpg")).willReturn(false);

      assertThatThrownBy(() -> fileController.confirmProfileImageUpload(
          new UserPrincipal(USER_ID), new ProfileImageConfirmRequest("profile/1/missing.jpg")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(FileErrorCode.UPLOAD_CONFIRM_FAILED);

      verify(userService, never()).updateProfileImage(any(), any());
    }
  }
}
