package com.remake.gone.auth.utils;

import java.security.SecureRandom;

/** 랜덤 숫자 6자리 문자열을 생성하는 유틸리티 클래스. */
public final class RandomCodeGenerator {

  private static final int CODE_LENGTH = 6;
  private static final int CODE_BOUND = 1_000_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  private RandomCodeGenerator() {
  }

  /**
   * 6자리 숫자로 구성된 랜덤 문자열을 생성합니다.
   *
   * @return "000000"부터 "999999"까지의 6자리 숫자 문자열
   */
  public static String generate() {
    int code = RANDOM.nextInt(CODE_BOUND);
    return String.format("%0" + CODE_LENGTH + "d", code);
  }
}
