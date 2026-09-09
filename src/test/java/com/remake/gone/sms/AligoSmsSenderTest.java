package com.remake.gone.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.sms.config.AligoProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * {@link AligoSmsSender}에 대한 단위 테스트. {@link MockRestServiceServer}로 알리고 API
 * 응답을 흉내 내어 검증한다({@link com.remake.gone.neis.NeisClientTest}와 동일한 패턴).
 */
class AligoSmsSenderTest {

  private static final String PHONE_NUMBER = "01099999999";
  private static final String MESSAGE = "[GONE] 인증번호는 123456 입니다.";

  @Nested
  @DisplayName("send")
  class Send {

    private MockRestServiceServer mockServer;
    private AligoSmsSender aligoSmsSender;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
      RestClient.Builder builder = RestClient.builder().baseUrl("https://apis.aligo.in");
      mockServer = MockRestServiceServer.bindTo(builder).build();
      AligoProperties properties = new AligoProperties("test-key", "test-user-id", "01000000000");
      aligoSmsSender = new AligoSmsSender(builder.build(), properties);

      logAppender = new ListAppender<>();
      logAppender.start();
      logger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
      logger().detachAppender(logAppender);
    }

    private Logger logger() {
      return (Logger) LoggerFactory.getLogger(AligoSmsSender.class);
    }

    @Test
    @DisplayName("정상 응답(result_code >= 0)이면 요청 폼 바디를 그대로 실어 보내고 예외 없이 종료한다")
    void sendsSuccessfully() {
      MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
      expectedForm.add("key", "test-key");
      expectedForm.add("user_id", "test-user-id");
      expectedForm.add("sender", "01000000000");
      expectedForm.add("receiver", PHONE_NUMBER);
      expectedForm.add("msg", MESSAGE);

      mockServer.expect(requestTo(containsString("/send/")))
          .andExpect(content().formData(expectedForm))
          .andRespond(withSuccess(
              """
                  {"result_code":1,"message":"success","msg_id":100}
                  """, MediaType.APPLICATION_JSON));

      assertThatCode(() -> aligoSmsSender.send(PHONE_NUMBER, MESSAGE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실패 응답(result_code < 0)이면 SMS_SEND_FAILED를 던진다")
    void throwsWhenResultCodeNegative() {
      mockServer.expect(requestTo(containsString("/send/")))
          .andRespond(withSuccess(
              """
                  {"result_code":-101,"message":"발신번호가 등록되지 않았습니다."}
                  """, MediaType.APPLICATION_JSON));

      assertThatThrownBy(() -> aligoSmsSender.send(PHONE_NUMBER, MESSAGE))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.SMS_SEND_FAILED);
    }

    @Test
    @DisplayName("네트워크/서버 오류면 SMS_SEND_FAILED를 던진다")
    void throwsOnServerError() {
      mockServer.expect(requestTo(containsString("/send/")))
          .andRespond(withServerError());

      assertThatThrownBy(() -> aligoSmsSender.send(PHONE_NUMBER, MESSAGE))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.SMS_SEND_FAILED);
    }

    @Test
    @DisplayName("네트워크/서버 오류 로그에 수신자 전화번호를 남기지 않는다")
    void doesNotLogPhoneNumberOnServerError() {
      mockServer.expect(requestTo(containsString("/send/")))
          .andRespond(withServerError());

      assertThatThrownBy(() -> aligoSmsSender.send(PHONE_NUMBER, MESSAGE))
          .isInstanceOf(CustomException.class);

      assertThat(logAppender.list).hasSize(1);
      ILoggingEvent event = logAppender.list.get(0);
      assertThat(event.getLevel()).isEqualTo(Level.ERROR);
      assertThat(event.getFormattedMessage()).doesNotContain(PHONE_NUMBER);
    }

    @Test
    @DisplayName("응답은 200이지만 바디가 없으면 NPE 대신 SMS_SEND_FAILED를 던진다")
    void throwsWhenBodyIsEmpty() {
      mockServer.expect(requestTo(containsString("/send/")))
          .andRespond(withSuccess());

      assertThatThrownBy(() -> aligoSmsSender.send(PHONE_NUMBER, MESSAGE))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.SMS_SEND_FAILED);
    }

    @Test
    @DisplayName("응답 바디에 result_code 필드가 없으면 SMS_SEND_FAILED를 던진다")
    void throwsWhenResultCodeMissing() {
      mockServer.expect(requestTo(containsString("/send/")))
          .andRespond(withSuccess(
              """
                  {"message":"unexpected"}
                  """, MediaType.APPLICATION_JSON));

      assertThatThrownBy(() -> aligoSmsSender.send(PHONE_NUMBER, MESSAGE))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(AuthErrorCode.SMS_SEND_FAILED);
    }
  }
}
