package com.remake.gone.common.config.r2;

import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Cloudflare R2(S3 호환) 연동을 위한 클라이언트 설정.
 */
@Configuration
@RequiredArgsConstructor
public class R2Config {

  private final R2Properties r2Properties;

  /**
   * R2 버킷에 직접 접근하기 위한 S3 클라이언트.
   *
   * @return 구성된 {@link S3Client}
   */
  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(r2Properties.endpoint()))
        .region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(r2Properties.accessKey(), r2Properties.secretKey())))
        .build();
  }

  /**
   * presigned URL 발급을 위한 S3 presigner.
   *
   * @return 구성된 {@link S3Presigner}
   */
  @Bean
  public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .endpointOverride(URI.create(r2Properties.endpoint()))
        .region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(r2Properties.accessKey(), r2Properties.secretKey())))
        .build();
  }
}