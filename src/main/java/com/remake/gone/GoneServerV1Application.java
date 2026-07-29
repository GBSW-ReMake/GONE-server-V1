package com.remake.gone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** GONE 서버 애플리케이션 진입점. */
@SpringBootApplication
public class GoneServerV1Application {

  /**
   * 애플리케이션을 시작합니다.
   *
   * @param args 커맨드라인 인수
   */
  public static void main(String[] args) {
    SpringApplication.run(GoneServerV1Application.class, args);
  }

}
