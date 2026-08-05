package com.remake.gone.file.controller;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.common.security.UserPrincipal;
import com.remake.gone.file.dto.ImageUploadUrlRequest;
import com.remake.gone.file.dto.ImageUploadUrlResponse;
import com.remake.gone.file.dto.ProfileImageConfirmRequest;
import com.remake.gone.file.exception.FileErrorCode;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 파일(프로필 이미지) 업로드 관련 API 컨트롤러.
 *
 * <p>프로필 사진은 로그인 이후 자유롭게 바꾸는 흐름으로 확정되어, 두 엔드포인트 모두 Access
 * Token 인증이 필요하다({@code SecurityConfig} 참고).
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

  private final R2FileService r2FileService;
  private final UserService userService;

  /**
   * 프로필 이미지 업로드용 presigned URL을 발급합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param request   업로드할 파일의 이름/타입/크기 정보
   * @return 업로드 URL과 저장 key
   */
  @PostMapping("/profile-image/upload-url")
  public ApiResponse<ImageUploadUrlResponse> getProfileImageUploadUrl(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody ImageUploadUrlRequest request
  ) {
    String keyPrefix = "profile/" + principal.userId();
    ImageUploadUrlResponse response = r2FileService.generateUploadUrl(
        keyPrefix, request.fileName(), request.contentType(), request.fileSize()
    );
    return ApiResponse.success(response, "업로드 URL이 발급되었습니다.");
  }

  /**
   * 클라이언트가 presigned URL로 업로드를 마친 프로필 이미지를 확정하고, 본인 계정에 저장합니다.
   *
   * @param principal 인증 필터가 Access Token에서 추출한 현재 사용자
   * @param request   업로드된 객체의 key
   * @return 확정 결과 응답
   */
  @PostMapping("/profile-image/confirm")
  public ApiResponse<Void> confirmProfileImageUpload(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody ProfileImageConfirmRequest request
  ) {
    boolean exists = r2FileService.checkObjectExists(request.key());
    if (!exists) {
      throw new CustomException(FileErrorCode.UPLOAD_CONFIRM_FAILED);
    }
    userService.updateProfileImage(principal.userId(), request.key());
    return ApiResponse.success(null, "업로드가 확인되었습니다.");
  }
}