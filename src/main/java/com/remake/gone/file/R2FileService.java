package com.remake.gone.file;

import com.remake.gone.common.config.r2.R2Properties;
import com.remake.gone.file.dto.PresignedUploadResult;
import java.time.Duration;
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

  /**
   * 업로드용 presigned URL 발급
   * @param keyPrefix 예: "profile/{studentId}"
   * @param originalFileName 클라이언트가 보낸 원본 파일명 (확장자 추출용)
   * @param contentType 예: "image/jpeg"
   * @return presigned URL + 실제 저장된 key
   */
  public PresignedUploadResult generateUploadUrl(String keyPrefix, String originalFileName, String contentType) {
    String extension = extractExtension(originalFileName);
    String key = "%s/%s%s".formatted(keyPrefix, UUID.randomUUID(), extension);

    PutObjectRequest objectRequest = PutObjectRequest.builder()
        .bucket(r2Properties.bucket())
        .key(key)
        .contentType(contentType)
        .build();

    PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
        .signatureDuration(UPLOAD_URL_DURATION)
        .putObjectRequest(objectRequest)
        .build();

    String url = s3Presigner.presignPutObject(presignRequest).url().toString();

    return new PresignedUploadResult(url, key);
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