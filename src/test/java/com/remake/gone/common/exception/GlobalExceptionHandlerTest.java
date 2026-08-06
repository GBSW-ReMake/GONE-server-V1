package com.remake.gone.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.remake.gone.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * {@link GlobalExceptionHandler}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Mock
  private HttpServletRequest request;

  @Nested
  @DisplayName("handleException")
  class HandleException {

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
      logAppender = new ListAppender<>();
      logAppender.start();
      logger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
      logger().detachAppender(logAppender);
    }

    private Logger logger() {
      return (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    }

    @Test
    @DisplayName("500 COMMON_007 응답은 기존과 동일하게 유지한다")
    void returnsInternalServerError() {
      ResponseEntity<ApiResponse<Void>> response =
          handler.handleException(new RuntimeException("boom"), request);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
      assertThat(response.getBody().success()).isFalse();
      assertThat(response.getBody().code()).isEqualTo("COMMON_007");
    }

    @Test
    @DisplayName("요청 메서드/경로와 예외 스택트레이스를 ERROR 레벨로 로깅한다")
    void logsRequestContextAndStackTrace() {
      given(request.getMethod()).willReturn("GET");
      given(request.getRequestURI()).willReturn("/api/v1/example");
      RuntimeException exception = new RuntimeException("boom");

      handler.handleException(exception, request);

      assertThat(logAppender.list).hasSize(1);
      ILoggingEvent event = logAppender.list.get(0);
      assertThat(event.getLevel()).isEqualTo(Level.ERROR);
      assertThat(event.getFormattedMessage()).contains("GET").contains("/api/v1/example");
      assertThat(event.getThrowableProxy().getClassName())
          .isEqualTo(RuntimeException.class.getName());
    }
  }

  @Nested
  @DisplayName("handleMissingServletRequestParameter")
  class HandleMissingServletRequestParameter {

    @Test
    @DisplayName("필수 요청 파라미터가 없으면 500이 아니라 400 COMMON_001로 응답한다")
    void returns400InsteadOfInternalServerError() {
      ResponseEntity<ApiResponse<Void>> response = handler.handleMissingServletRequestParameter(
          new MissingServletRequestParameterException("query", "String"));

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(response.getBody().success()).isFalse();
      assertThat(response.getBody().code()).isEqualTo("COMMON_001");
    }
  }
}
