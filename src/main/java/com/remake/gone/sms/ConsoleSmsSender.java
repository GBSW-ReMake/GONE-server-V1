package com.remake.gone.sms;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발 환경에서 사용하는 콘솔 출력 기반 {@link SmsSender} 구현체.
 *
 * <p>실제로 문자를 발송하지 않고 콘솔에 로그를 출력합니다.
 */
@Component
@Profile("dev")
public class ConsoleSmsSender implements SmsSender {

  @Override
  public void send(String phoneNumber, String message) {
    System.out.println("[SMS] " + phoneNumber + ": " + message);
  }

}
