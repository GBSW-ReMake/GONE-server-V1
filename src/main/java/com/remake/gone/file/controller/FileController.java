package com.remake.gone.file.controller;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.ApiResponse;
import com.remake.gone.file.dto.ImageUploadUrlRequest;
import com.remake.gone.file.dto.ImageUploadUrlResponse;
import com.remake.gone.file.dto.ProfileImageConfirmRequest;
import com.remake.gone.file.exception.FileErrorCode;
import com.remake.gone.file.service.R2FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 파일(프로필 이미지) 업로드 관련 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

  private final R2FileService r2FileService;

  /**
   * 프로필 이미지 업로드용 presigned URL을 발급합니다.
   *
   * @param request 업로드할 파일의 이름/타입/크기 정보
   * @return 업로드 URL과 저장 key
   */
  @PostMapping("/profile-image/upload-url")
  public ApiResponse<ImageUploadUrlResponse> getProfileImageUploadUrl(
      // TODO: 인증 붙으면 @AuthenticationPrincipal로 studentId 받아서 keyPrefix에 포함
      @RequestBody ImageUploadUrlRequest request
  ) {
    // TODO: Flyway(V1__init_auth.sql)에는 student/user 테이블이 정의돼 있지만
    //  아직 JPA Entity(Student/User)를 만들지 않아 studentId를 조회/검증할 수 없다.
    //  Entity 생성 후 studentId 기반 keyPrefix로 교체할 것.
    String keyPrefix = "profile/temp"; // 인증 붙기 전 임시값, 다음 단계에서 studentId로 교체
    ImageUploadUrlResponse response = r2FileService.generateUploadUrl(
        keyPrefix, request.fileName(), request.contentType(), request.fileSize()
    );
    return ApiResponse.success(response, "업로드 URL이 발급되었습니다.");
  }

  /**
   * 클라이언트가 presigned URL로 업로드를 마친 프로필 이미지를 확정합니다.
   *
   * @param request 업로드된 객체의 key
   * @return 확정 결과 응답
   */
  @PostMapping("/profile-image/confirm")
  public ApiResponse<Void> confirmProfileImageUpload(
      @RequestBody ProfileImageConfirmRequest request) {
    boolean exists = r2FileService.checkObjectExists(request.key());
    if (!exists) {
      throw new CustomException(FileErrorCode.UPLOAD_CONFIRM_FAILED);
    }
    // TODO: Flyway(V1__init_auth.sql)에는 student/user 테이블이 정의돼 있지만
    //  아직 JPA Entity(Student/User)를 만들지 않아 여기서 프로필 이미지 URL을
    //  User 레코드에 저장하는 로직을 붙일 수 없다.
    //  Entity 생성 후, 이 key로 만든 조회용 URL을 User.profileImageUrl에 저장하는 로직 연결할 것.
    return ApiResponse.success(null, "업로드가 확인되었습니다.");
  }
}