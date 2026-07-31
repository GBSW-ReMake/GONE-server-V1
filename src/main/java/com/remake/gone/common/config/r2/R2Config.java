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

@Configuration
@RequiredArgsConstructor
public class R2Config {

  private final R2Properties r2Properties;

  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(r2Properties.endpoint()))
        .region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(r2Properties.accessKey(), r2Properties.secretKey())))
        .build();
  }

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