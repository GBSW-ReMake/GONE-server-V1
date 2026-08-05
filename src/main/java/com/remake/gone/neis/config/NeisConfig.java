package com.remake.gone.neis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 나이스(NEIS) 오픈 API 연동을 위한 HTTP 클라이언트 설정.
 */
@Configuration
public class NeisConfig {

  /**
   * NEIS 오픈 API 호출 전용 {@link RestClient}.
   *
   * @return {@code https://open.neis.go.kr/hub}를 baseUrl로 하는 {@link RestClient}
   */
  @Bean
  public RestClient neisRestClient() {
    return RestClient.builder()
        .baseUrl("https://open.neis.go.kr/hub")
        .build();
  }
}
