package com.remake.gone.sms;

import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.sms.config.AligoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * 알리고(Aligo) SMS API로 실제 문자를 발송하는 {@link SmsSender} 구현체.
 *
 * <p>{@link ConsoleSmsSender}가 {@code dev} 프로필을 전담하므로, 이 클래스는 정확히 그
 * 여집합인 {@code dev}가 아닌 모든 프로필({@code staging}, 향후 {@code prod})에서 활성화된다.
 */
@Component
@Profile("!dev")
@RequiredArgsConstructor
@Slf4j
public class AligoSmsSender implements SmsSender {

  private final RestClient aligoRestClient;
  private final AligoProperties aligoProperties;

  @Override
  public void send(String phoneNumber, String message) {
    JsonNode body;
    try {
      body = aligoRestClient.post()
          .uri("/send/")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(formBody(phoneNumber, message))
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientException e) {
      log.error("Aligo SMS API 호출 실패: phoneNumber={}", phoneNumber, e);
      throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
    }

    int resultCode = body.path("result_code").asInt();
    if (resultCode < 0) {
      log.error("Aligo SMS 발송 실패: result_code={}, message={}",
          resultCode, body.path("message").asText(""));
      throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
    }
  }

  private MultiValueMap<String, String> formBody(String phoneNumber, String message) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("key", aligoProperties.key());
    form.add("user_id", aligoProperties.userId());
    form.add("sender", aligoProperties.sender());
    form.add("receiver", phoneNumber);
    form.add("msg", message);
    return form;
  }
}
