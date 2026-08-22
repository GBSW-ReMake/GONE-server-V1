package com.remake.gone.sms;

/**
 * 문자 메시지 발송을 담당하는 인터페이스.
 *
 * <p>구현체를 교체하여 콘솔 출력, 실제 SMS 발송사 연동 등 다양한 발송 방식을 지원합니다.
 */
public interface SmsSender {

  /**
   * 지정한 휴대폰 번호로 메시지를 발송합니다.
   *
   * @param phoneNumber 수신자 휴대폰 번호
   * @param message     발송할 메시지 내용
   */
  void send(String phoneNumber, String message);
}
