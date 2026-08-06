package com.remake.gone.outing.utils;

import java.security.SecureRandom;

/** 외출증 외부 식별자로 쓸 영숫자 10자리 랜덤 코드를 생성하는 유틸리티 클래스. */
public final class OutingCodeGenerator {

  private static final int CODE_LENGTH = 10;
  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private OutingCodeGenerator() {
  }

  /**
   * 영숫자(대소문자 + 숫자) 10자리 랜덤 코드를 생성합니다.
   *
   * @return 예: {@code "8A1zx9202n"}
   */
  public static String generate() {
    StringBuilder builder = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return builder.toString();
  }
}
