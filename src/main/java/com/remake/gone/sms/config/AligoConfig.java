package com.remake.gone.sms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 알리고(Aligo) SMS API 연동을 위한 HTTP 클라이언트 설정.
 */
@Configuration
public class AligoConfig {

  /**
   * 알리고 SMS API 호출 전용 {@link RestClient}.
   *
   * @return {@code https://apis.aligo.in}를 baseUrl로 하는 {@link RestClient}
   */
  @Bean
  public RestClient aligoRestClient() {
    return RestClient.builder()
        .baseUrl("https://apis.aligo.in")
        .build();
  }
}
