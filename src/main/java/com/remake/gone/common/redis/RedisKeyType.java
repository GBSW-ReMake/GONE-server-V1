package com.remake.gone.common.redis;

import java.time.Duration;

/** 프로젝트 전체에서 사용하는 Redis 키 접두사와 TTL을 한곳에서 관리한다. */
public enum RedisKeyType {
  /** 휴대폰 인증번호 저장 키. value: 인증번호(6자리 숫자 문자열), TTL 5분. */
  PHONE_VERIFICATION("auth:phone:verify:", Duration.ofMinutes(5)),

  /** 휴대폰 인증 성공 후 발급하는 회원가입용 티켓 저장 키. value: 인증된 휴대폰 번호, TTL 10분. */
  SIGN_UP_TICKET("auth:signup:ticket:", Duration.ofMinutes(10)),

  /** 인증번호 재발송 쿨다운 키. value 없이 존재 여부만 사용, TTL 30초. */
  PHONE_SEND_COOLDOWN("auth:phone:send-cooldown:", Duration.ofSeconds(30)),

  /** 휴대폰 인증번호 확인 실패 횟수 저장 키. value: 누적 실패 횟수, TTL 5분. */
  PHONE_VERIFICATION_FAIL_COUNT("auth:phone:fail-count:", Duration.ofMinutes(5));

  private final String prefix;
  private final Duration ttl;

  RedisKeyType(String prefix, Duration ttl) {
    this.prefix = prefix;
    this.ttl = ttl;
  }

  /** 실제 키값을 만든다 (prefix + 식별자). */
  public String key(String identifier) {
    return prefix + identifier;
  }

  /** TTL을 반환한다. */
  public Duration ttl() {
    return ttl;
  }
}
