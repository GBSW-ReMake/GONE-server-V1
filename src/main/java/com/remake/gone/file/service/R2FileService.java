package com.remake.gone.file.service;

import com.remake.gone.common.config.r2.R2Properties;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.file.dto.ImageUploadUrlResponse;
import com.remake.gone.file.exception.FileErrorCode;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
@RequiredArgsConstructor
public class R2FileService {

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final R2Properties r2Properties; // Step 3에서 만든 record

  private static final Duration UPLOAD_URL_DURATION = Duration.ofMinutes(10);
  private static final Duration DOWNLOAD_URL_DURATION = Duration.ofMinutes(15);
  private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
      "image/jpeg",
      "image/png"
  );
  private static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5MB

  /**
   * 프로필 이미지 업로드용 presigned URL을 발급한다.
   *
   * <p><b>프론트엔드 사용법 요약</b>
   * <ol>
   *   <li>{@code POST /api/v1/files/profile-image/upload-url}에 업로드할 파일의
   *       {@code fileName}, {@code contentType}(예: {@code "image/jpeg"}),
   *       {@code fileSize}(byte 단위 실제 파일 크기)를 담아 요청한다.</li>
   *   <li>응답으로 받은 {@code uploadUrl}에, 요청 때 보낸 것과 <b>동일한 {@code Content-Type}과
   *       {@code Content-Length}(= fileSize)</b>를 헤더로 실어 원본 파일 바이트를 그대로
   *       {@code PUT}한다. 이 값이 다르면 R2가 서명 불일치로 업로드를 거부한다.</li>
   *   <li>업로드가 끝나면 응답에 함께 온 {@code key}를 가지고
   *       {@code POST /api/v1/files/profile-image/confirm}을 호출해 업로드를 확정한다.</li>
   * </ol>
   *
   * <p>{@code contentType}은 {@link #ALLOWED_IMAGE_TYPES} 화이트리스트에 없으면,
   * {@code fileSize}가 {@link #MAX_FILE_SIZE_BYTES}(5MB)를 넘으면 각각
   * {@link FileErrorCode#UNSUPPORTED_FILE_TYPE}, {@link FileErrorCode#FILE_TOO_LARGE}를 던진다.
   *
   * @param keyPrefix        예: "profile/{studentId}"
   * @param originalFileName 클라이언트가 보낸 원본 파일명 (확장자 추출용)
   * @param contentType      예: "image/jpeg"
   * @param fileSize         업로드할 파일의 크기(byte)
   * @return presigned URL + 실제 저장된 key
   */
  public ImageUploadUrlResponse generateUploadUrl(
      String keyPrefix, String originalFileName, String contentType, long fileSize) {
    validateContentType(contentType);
    validateFileSize(fileSize);

    String extension = extractExtension(originalFileName);
    String key = "%s/%s%s".formatted(keyPrefix, UUID.randomUUID(), extension);

    PutObjectRequest objectRequest = PutObjectRequest.builder()
        .bucket(r2Properties.bucket())
        .key(key)
        .contentType(contentType)
        .contentLength(fileSize)
        .build();

    PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
        .signatureDuration(UPLOAD_URL_DURATION)
        .putObjectRequest(objectRequest)
        .build();

    String url = s3Presigner.presignPutObject(presignRequest).url().toString();

    return new ImageUploadUrlResponse(url, key);
  }

  private void validateContentType(String contentType) {
    if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
      throw new CustomException(FileErrorCode.UNSUPPORTED_FILE_TYPE);
    }
  }

  private void validateFileSize(long fileSize) {
    if (fileSize <= 0 || fileSize > MAX_FILE_SIZE_BYTES) {
      throw new CustomException(FileErrorCode.FILE_TOO_LARGE);
    }
  }

  /**
   * 조회용 presigned URL 발급
   */
  public String generateDownloadUrl(String key) {
    GetObjectRequest objectRequest = GetObjectRequest.builder()
        .bucket(r2Properties.bucket())
        .key(key)
        .build();

    GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
        .signatureDuration(DOWNLOAD_URL_DURATION)
        .getObjectRequest(objectRequest)
        .build();

    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }

  /**
   * 실제로 R2에 파일이 존재하는지 확인 (업로드 완료 확인용)
   */
  public boolean checkObjectExists(String key) {
    try {
      s3Client.headObject(HeadObjectRequest.builder()
          .bucket(r2Properties.bucket())
          .key(key)
          .build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }

  private String extractExtension(String fileName) {
    int dotIndex = fileName.lastIndexOf('.');
    return dotIndex == -1 ? "" : fileName.substring(dotIndex);
  }
}